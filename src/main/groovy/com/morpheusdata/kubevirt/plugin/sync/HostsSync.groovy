package com.morpheusdata.kubevirt.plugin.sync

import groovy.util.logging.Slf4j
import com.morpheusdata.kubevirt.plugin.KubevirtPlugin
import com.morpheusdata.kubevirt.plugin.utils.*
import com.morpheusdata.model.Cloud
import com.morpheusdata.core.*
import com.morpheusdata.model.*
import com.morpheusdata.model.projection.*
import com.morpheusdata.core.util.SyncTask
import io.reactivex.*

// Kubernetes API
import io.fabric8.kubernetes.client.Config;
import io.fabric8.kubernetes.client.ConfigBuilder;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.KubernetesClientException;
import io.fabric8.kubernetes.client.KubernetesClientBuilder;
import io.fabric8.kubernetes.client.DefaultKubernetesClient;
import io.fabric8.kubernetes.client.utils.HttpClientUtils
import io.fabric8.kubernetes.client.http.HttpClient;
import io.fabric8.kubernetes.client.okhttp.OkHttpClientFactory;
import io.fabric8.kubernetes.client.*;
import io.fabric8.kubernetes.api.model.*
import io.fabric8.kubernetes.api.model.apiextensions.v1.CustomResourceDefinition;
import io.fabric8.kubernetes.api.model.apiextensions.v1.CustomResourceDefinitionList;
import io.fabric8.kubernetes.client.dsl.base.CustomResourceDefinitionContext;
import io.fabric8.kubernetes.client.dsl.base.*
import io.fabric8.kubernetes.api.model.apps.Deployment;
import io.fabric8.kubernetes.api.model.apps.DeploymentBuilder;


@Slf4j
class HostsSync {

	private Cloud cloud
	private MorpheusContext morpheusContext
	private KubevirtPlugin plugin
	private Map authConfig

	public HostsSync(KubevirtPlugin kubevirtPlugin, Cloud cloud) {
		this.plugin = kubevirtPlugin
		this.cloud = cloud
		this.morpheusContext = kubevirtPlugin.morpheusContext
	}

	def execute() {
		log.info "execute HostsSync: ${cloud}"

		try {
			this.authConfig = plugin.getAuthConfig(cloud)

			ConfigBuilder configBuilder = new ConfigBuilder();
			Config config = configBuilder
			  .withMasterUrl(authConfig.apiUrl)
			  .withTrustCerts(true)
			  .withOauthToken(cloud.configMap.serviceToken)
			  .build();
			OkHttpClientFactory factory = new OkHttpClientFactory()
			KubernetesClient client = new KubernetesClientBuilder().withConfig(config).withHttpClientFactory(factory).build();

			List<Node> items = client.nodes().list().getItems();
			def cloudItems = []
			for (Node item : items) {
				def hostinfo = [:]
				//log.info "Host Info: ${item.getMetadata()}"
				//log.info "Host Spec: ${item.getSpec()}"
				log.info "Host Status: ${item.getStatus()}"
				//log.info "Host Addresses: ${item.getStatus().getAddresses()}"
				List<NodeAddress> addresses = item.getStatus().getAddresses();
				for (NodeAddress address : addresses) {
					log.info "Address Type: ${address.getType()}"
					log.info "Address Data: ${address.getAddress()}"
					def addType = address.getType()
					if (addType == "Hostname") {
						hostinfo["name"] = address.getAddress()
					}
					if (addType == "InternalIP"){
						hostinfo["hypervisor_address"] = address.getAddress()
					}
				}
				log.info "Host Capacity: ${item.getStatus().getCapacity()}"
				def capacity = item.getStatus().getCapacity()
				log.info "Host CPU: ${capacity['cpu']}"
				hostinfo["num_cpu_cores"] = capacity['cpu'].toString()
				//def memory = capacity['memory']
				//hostinfo["memory_capacity_in_bytes"] = memory.replace("Ki", "")
				log.info "Host Allocations: ${item.getStatus().getAllocatable()}"
				log.info "Host Node Info: ${item.getStatus().getNodeInfo()}"
				cloudItems << hostinfo
			}
			def queryResults = [:]

			queryResults.serverType = new ComputeServerType(code: 'kubevirt-hypervisor')
			queryResults.serverOs = new OsType(code: 'linux')

			log.info "Cloud Items: ${cloudItems}"
			
			//log.info "Host Sync List Status: ${listResultSuccess}"
			//if (listResultSuccess) {
				def domainRecords = morpheusContext.computeServer.listIdentityProjections(cloud.id, null).filter { ComputeServerIdentityProjection projection ->
					if (projection.category == "kubevirt.host.${cloud.id}") {
						return true
					}
					false
				}
				//Observable domainRecords = morpheusContext.computeServer.listSyncProjections(cloud.id).filter { ComputeServerIdentityProjection projection ->
				//	if (projection.category == "kubevirt.host.${cloud.id}") {
				//		return true
				//	}
				//	false
				//}
				SyncTask<ComputeServerIdentityProjection, Map, ComputeServer> syncTask = new SyncTask<>(domainRecords, cloudItems)
				syncTask.addMatchFunction { ComputeServerIdentityProjection domainObject, Map cloudItem ->
					domainObject.externalId == cloudItem?.ref.toString()
				}.withLoadObjectDetails { List<SyncTask.UpdateItemDto<ComputeServerIdentityProjection, Map>> updateItems ->
					Map<Long, SyncTask.UpdateItemDto<ComputeServerIdentityProjection, Map>> updateItemMap = updateItems.collectEntries { [(it.existingItem.id): it] }
					morpheusContext.computeServer.listById(updateItems?.collect { it.existingItem.id }).map { ComputeServer server ->
						SyncTask.UpdateItemDto<ComputeServerIdentityProjection, Map> matchItem = updateItemMap[server.id]
						return new SyncTask.UpdateItem<ComputeServer, Map>(existingItem: server, masterItem: matchItem.masterItem)
					}
				}.onAdd { itemsToAdd ->
					addMissingHosts(cloud, itemsToAdd)
				}.onUpdate { List<SyncTask.UpdateItem<ComputeServer, Map>> updateItems ->
					updateMatchedHosts(cloud, updateItems)
				}.onDelete { removeItems ->
					removeMissingHosts(cloud, removeItems)
				}.start()
			//}
		} catch(e) {
			log.error "Error in execute : ${e}", e
		}
	}

	def removeMissingHosts(Cloud cloud, List removeList) {
		log.debug "removeMissingHosts: ${cloud} ${removeList.size()}"
		morpheusContext.computeServer.remove(removeList).blockingGet()
	}

	def updateMatchedHosts(Cloud cloud, List updateList) {
		log.debug "updateMatchedHosts: ${cloud} ${updateList.size()}"

		List<ComputeZonePoolIdentityProjection> zoneClusters = []
		def clusterNames = updateList.collect{it.masterItem.cluster}.unique()
		morpheusContext.cloud.pool.listSyncProjections(cloud.id, null).filter {
			it.type == 'Cluster' && it.internalId in clusterNames
		}.blockingSubscribe { zoneClusters << it }

		def statsData = []
		for(update in updateList) {
			ComputeServer currentServer = update.existingItem
			def matchedServer = update.masterItem
			if(currentServer) {
				def save = false
				def clusterObj = zoneClusters?.find { pool -> pool.internalId == update.masterItem.cluster }
				if(currentServer.resourcePool?.id != clusterObj.id) {
					currentServer.resourcePool = new ComputeZonePool(id: clusterObj.id)
					save = true
				}
				def hostUuid = matchedServer.uuid
				if(hostUuid && currentServer.uniqueId != hostUuid) {
					currentServer.uniqueId = hostUuid
					save = true
				}
				if(save) {
					morpheusContext.computeServer.save([currentServer]).blockingGet()
				}
				syncHostDatastoreVolumes(currentServer, matchedServer, volumeType)
				updateHostStats(currentServer, matchedServer)
			}
		}
	}

    // Add Hypervisor Hosts
	def addMissingHosts(Cloud cloud, List addList) {
		log.info "addMissingHosts: ${cloud} ${addList.size()}"

//		def volumeType = new StorageVolumeType(code: 'vmware-plugin-datastore')
		def serverType = new ComputeServerType(code: 'kubevirt-hypervisor')
		def serverOs = new OsType(code: 'linux')
		for(cloudItem in addList) {
			try {
				def serverConfig = [
					account:cloud.owner, 
					category:"kubevirt.host.${cloud.id}", 
					cloud: cloud,
					name: cloudItem.name, 
					externalId: cloudItem.name, 
					uniqueId: cloudItem.name, 
					sshUsername:'root', 
					status:'provisioned',
					provision:false, 
					serverType:'hypervisor', 
					computeServerType:serverType, 
					serverOs:serverOs,
					osType:'linux', 
					hostname: cloudItem.name,
					externalIp: cloudItem.hypervisor_address
				]
				def newServer = new ComputeServer(serverConfig)
				if(!morpheusContext.computeServer.create([newServer]).blockingGet()){
					log.error "Error in creating host server ${newServer}"
				}
				//def (maxStorage, usedStorage) = syncHostVolumes(newServer, volumeType, cloudHostDisks)
				updateMachineMetrics(
						newServer,
						//cloudItem.num_cpu_cores
						cloudItem.num_cpu_cores?.toLong(),
						//maxStorage?.toLong(),
						//usedStorage?.toLong(),
			//			cloudItem.memory_capacity_in_bytes?.toLong(),
						//((cloudItem.memory_capacity_in_bytes ?: 0 ) * (cloudItem.stats.hypervisor_memory_usage_ppm?.toLong() / 1000000.0))?.toLong(),
						//(cloudItem.stats.hypervisor_cpu_usage_ppm?.toLong() / 10000.0)
				)
			} catch(e) {
				log.error "Error in creating host: ${e}", e
			}

			//syncHostDatastoreVolumes(newServer, cloudItem, volumeType)
		}
	}

	private syncHostDatastoreVolumes(ComputeServer server, host, StorageVolumeType volumeType) {
		log.debug "syncHostDatastoreVolumes: ${server} ${host} ${volumeType}"
		def existingVolumes = server.volumes
		def hostDatastores = host.datastores.findAll{it.accessible}
		def datastores = []
		if(hostDatastores) {
			def uniqueExternalIds = host.datastores.collect{ds -> ds.ref}
			morpheusContext.cloud.datastore.listSyncProjections(cloud.id).filter {
				it.externalId in uniqueExternalIds
			}.blockingSubscribe { datastores << it }
		}
		def addList = []
		def updateList = []
		hostDatastores.each { ds ->
			def match = existingVolumes.find {it.externalId == ds.ref}
			if(match) {
				def save = false
				if(match.maxStorage != ds.summary.capacity) {
					match.maxStorage = ds.summary.capacity
					save = true
				}
				if(match.name != ds.name) {
					match.name = ds.name
					save = true
				}
				if(save) {
					updateList << match
				}
			} else {
				def newVolume = new StorageVolume(
						[
								type      : volumeType,
								maxStorage: ds.summary.capacity,
								externalId: ds.ref,
								name      : ds.name
						]
				)
				newVolume.datastore = datastores?.find{dsobj -> dsobj.externalId == ds.ref}

				addList << newVolume
			}
		}
		if(updateList?.size() > 0) {
			log.debug "Saving ${updateList.size()} storage volumes"
			morpheusContext.storageVolume.save(updateList).blockingGet()
		}

		def removeList = existingVolumes.findAll{vol -> !hostDatastores.find{ds -> ds.ref == vol.externalId}}
		if(removeList?.size() > 0) {
			log.debug "Removing ${removeList.size()} storage volumes"
			morpheusContext.storageVolume.remove(removeList, server, false).blockingGet()
		}

		if(addList?.size() > 0) {
			log.debug "Adding ${addList.size()} storage volumes"
			morpheusContext.storageVolume.create(addList, server).blockingGet()
		}
	}

	private updateHostStats(ComputeServer server, hostMap) {
		log.debug "updateHostStats for ${server}"
		try {
			//storage
			def host = hostMap //.entity
			def datastores = host.datastores
			def maxStorage = 0
			def maxUsedStorage = 0
			def maxFreeStorage = 0
			datastores?.each { datastore ->
				def summary = datastore.summary
				if(summary && summary.accessible) {
					maxStorage += (summary.capacity ?: 0)
					maxFreeStorage += (summary.freeSpace ?: 0)
					maxUsedStorage = maxStorage - maxFreeStorage
				}
			}
			def runtime = host.runtime
			//general
			def vendor = host.summary?.hardware?.vendor
			def nicCount = host.summary?.hardware?.nicCount
			//cpu
			def maxCpu = host.summary?.hardware?.cpuMhz
			def cpuModel = host.summary?.hardware?.cpuModel
			def cpuCount = host.summary?.hardware?.cpuCount
			def threadCount = host.summary?.hardware?.threadCount
			def cpuCores = host.hardware?.cpuInfo?.numCpuCores ?: 1
			def maxUsedCpu = host.summary.quickStats?.getOverallCpuUsage()
			def cpuPercent = 0
			//getSummary()?.getHardware()?.getCpuMhz()
			//memory
			def maxMemory = host.hardware.memorySize ?: 0
			def maxUsedMemory = (host.summary?.quickStats?.getOverallMemoryUsage() ?: 0) * ComputeUtility.ONE_MEGABYTE
			//power state
			def power = runtime.powerState
			def powerState = 'unknown'
			if(power == HostSystemPowerState.poweredOn)
				powerState = 'on'
			else if(power == HostSystemPowerState.poweredOff)
				powerState = 'off'
			else if(power == HostSystemPowerState.standBy)
				powerState = 'paused'
			//save it all
			def updates = false
			def capacityInfo = server.capacityInfo ?: new ComputeCapacityInfo(maxMemory:maxMemory, maxStorage:maxStorage)
			if(maxMemory > server.maxMemory) {
				server.maxMemory = maxMemory
				capacityInfo?.maxMemory = maxMemory
				updates = true
			}
			if(maxUsedMemory != capacityInfo.usedMemory) {
				capacityInfo.usedMemory = maxUsedMemory
				server.usedMemory = maxUsedMemory
				updates = true
			}
			if(maxStorage > server.maxStorage) {
				server.maxStorage = maxStorage
				capacityInfo?.maxStorage = maxStorage
				updates = true
			}
			if(cpuCores != null && (server.maxCores == null || cpuCores > server.maxCores)) {
				server.maxCores = cpuCores
				capacityInfo.maxCores = cpuCores
				updates = true
			}
			//settings some host detail info - will save if other updates happen
			if(cpuModel)
				server.setConfigProperty('cpuModel', cpuModel)
			if(threadCount)
				server.setConfigProperty('threadCount', threadCount)
			if(nicCount)
				server.setConfigProperty('nicCount', nicCount)
			if(vendor)
				server.setConfigProperty('hardwareVendor', vendor)
			if(maxCpu)
				server.setConfigProperty('cpuMhz', maxCpu)
			if(cpuCount)
				server.setConfigProperty('cpuCount', cpuCount)
			//storage updates
			if(maxUsedStorage != capacityInfo.usedStorage) {
				capacityInfo.usedStorage = maxUsedStorage
				server.usedStorage = maxUsedStorage
				updates = true
			}
			if(server.powerState != powerState) {
				server.powerState = powerState
				updates = true
			}
			if(maxCpu && maxUsedCpu) {
				if(maxCpu > 0 && maxUsedCpu > 0) {
					cpuPercent = maxUsedCpu.div((maxCpu * cpuCores)) * 100
					if(cpuPercent > 100.0)
						cpuPercent = 100.0
					server.usedCpu = cpuPercent
					updates = true
				}
			}
			if(hostMap.hostname && hostMap.hostname != server.hostname) {
				server.hostname = hostMap.hostname
				updates = true
			}
			if(updates == true) {
				server.capacityInfo = capacityInfo
				morpheusContext.computeServer.save([server]).blockingGet()
			}
		} catch(e) {
			log.warn("error updating host stats: ${e}", e)
		}
	}

	def listHosts(Cloud cloud) {
		def rtn = [success:false]
		def authConfig = kubevirtPlugin.getAuthConfig(cloud)
		rtn = KubevirtComputeUtility.listHosts(authConfig.apiUrl, authConfig.apiToken)
		return rtn
	}

//	private updateMachineMetrics(ComputeServer server, Long maxCores, Long maxStorage, Long usedStorage, Long maxMemory, Long usedMemory, maxCpu) {
	private updateMachineMetrics(ComputeServer server, maxCpu) {
		log.debug "updateMachineMetrics for ${server}"
		try {
			def updates = !server.getComputeCapacityInfo()
			ComputeCapacityInfo capacityInfo = server.getComputeCapacityInfo() ?: new ComputeCapacityInfo()
/*
			if(capacityInfo.maxCores != maxCores || server.maxCores != maxCores) {
				capacityInfo.maxCores = maxCores
				server?.maxCores = maxCores
				updates = true
			}

			if(capacityInfo.maxStorage != maxStorage || server.maxStorage != maxStorage) {
				capacityInfo.maxStorage = maxStorage
				server?.maxStorage = maxStorage
				updates = true
			}

			if(capacityInfo.usedStorage != usedStorage || server.usedStorage != usedStorage) {
				capacityInfo.usedStorage = usedStorage
				server?.usedStorage = usedStorage
				updates = true
			}
			
			if(capacityInfo.maxMemory != maxMemory || server.maxMemory != maxMemory) {
				capacityInfo?.maxMemory = maxMemory
				server?.maxMemory = maxMemory
				updates = true
			}

			if(capacityInfo.usedMemory != usedMemory || server.usedMemory != usedMemory) {
				capacityInfo?.usedMemory = usedMemory
				server?.usedMemory = usedMemory
				updates = true
			}
*/
			if(capacityInfo.maxCpu != maxCpu || server.usedCpu != maxCpu) {
				capacityInfo?.maxCpu = maxCpu
				server?.usedCpu = maxCpu
				updates = true
			}

			// How to determine powerstate?!.. right now just using the cpu
			def powerState = capacityInfo.maxCpu > 0 ? ComputeServer.PowerState.on : ComputeServer.PowerState.off
			if(server.powerState != powerState) {
				server.powerState = powerState
				updates = true
			}

			if(updates == true) {
				server.capacityInfo = capacityInfo
				morpheusContext.computeServer.save([server]).blockingGet()
			}
		} catch(e) {
			log.warn("error updating host stats: ${e}", e)
		}
	}
}