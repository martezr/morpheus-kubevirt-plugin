package com.morpheusdata.kubevirt.plugin.sync

import com.morpheusdata.kubevirt.plugin.KubevirtPlugin
import com.morpheusdata.kubevirt.plugin.utils.*
import com.morpheusdata.core.MorpheusContext
import com.morpheusdata.core.util.SyncTask
import com.morpheusdata.model.Cloud
import com.morpheusdata.model.CloudPool
import com.morpheusdata.model.projection.CloudPoolIdentity
import groovy.util.logging.Slf4j
import io.reactivex.rxjava3.core.Observable
import io.reactivex.rxjava3.core.Single

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
class NamespacesSync {
	private Cloud cloud
	private MorpheusContext morpheusContext
	private KubevirtPlugin plugin
	private Map authConfig

	public NameSpaceSync(KubevirtPlugin kubevirtPlugin, Cloud cloud) {
		this.plugin = kubevirtPlugin
		this.cloud = cloud
		this.morpheusContext = kubevirtPlugin.morpheusContext
	}

	def execute() {
		log.info "BEGIN: execute NamespacesSync: ${cloud.id}"
		try {
			def authConfig = plugin.getAuthConfig(cloud)
            ConfigBuilder configBuilder = new ConfigBuilder();
            Config config = configBuilder
              .withMasterUrl(authConfig.apiUrl)
              .withTrustCerts(true)
              .withOauthToken(cloud.configMap.serviceToken)
              .build();
            OkHttpClientFactory factory = new OkHttpClientFactory()
            KubernetesClient client = new KubernetesClientBuilder().withConfig(config).withHttpClientFactory(factory).build();
            def cloudItems = []

            client.namespaces().list().getItems()
              .forEach{ namespace ->
                def hostinfo = [:]
                hostinfo["name"] = namespace.getMetadata().getName()
                cloudItems << hostinfo
              }
            

            Observable<CloudPoolIdentity> domainRecords = morpheusContext.async.cloud.pool.listIdentityProjections(cloud.id, "kubevirt.namespace.${cloud.id}", null)
            SyncTask<CloudPoolIdentity, Map, CloudPool> syncTask = new SyncTask<>(domainRecords, cloudItems)
            syncTask.addMatchFunction { CloudPoolIdentity domainObject, Map apiItem ->
                domainObject.externalId == apiItem.name
            }.onDelete { removeItems ->
                removeMissingResourcePools(removeItems)
            }.onUpdate { List<SyncTask.UpdateItem<CloudPool, Map>> updateItems ->
                updateMatchedResourcePools(updateItems)
            }.onAdd { itemsToAdd ->
                addMissingNamespaces(itemsToAdd)
            }.withLoadObjectDetails { List<SyncTask.UpdateItemDto<CloudPoolIdentity, Map>> updateItems ->
                Map<Long, SyncTask.UpdateItemDto<CloudPoolIdentity, Map>> updateItemMap = updateItems.collectEntries { [(it.existingItem.id): it]}
                morpheusContext.async.cloud.pool.listById(updateItems.collect { it.existingItem.id } as List<Long>).map {CloudPool cloudPool ->
                    SyncTask.UpdateItemDto<CloudPool, Map> matchItem = updateItemMap[cloudPool.id]
                    return new SyncTask.UpdateItem<CloudPool,Map>(existingItem:cloudPool, masterItem:matchItem.masterItem)
                }
            }.start()
		} catch(e) {
			log.error "Error in execute : ${e}", e
		}
		log.debug "END: execute ClustersSync: ${cloud.id}"
	}

	def addMissingNamespaces(List addList) {
		log.info "addMissingNamespaces ${cloud} ${addList.size()}"
		def adds = []

		for(cloudItem in addList) {
			def poolConfig = [
					owner     : cloud.owner,
					type      : 'vpc',
					name      : cloudItem.name,
					externalId: cloudItem.name,
					uniqueId  : cloudItem.name,
					internalId: cloudItem.name,
					refType   : 'ComputeZone',
					refId     : cloud.id,
					cloud     : cloud,
					category  : "kubevirt.namespace.${cloud.id}",
					code      : "kubevirt.namespace.${cloud.id}.${cloudItem.name}",
					readOnly  : true,
			]

			def add = new CloudPool(poolConfig)
			adds << add
		}

		//if(adds) {
			morpheusContext.async.cloud.pool.bulkCreate(adds).blockingGet()
		//}
	}

	private updateMatchedNamespaces(List updateList) {
		log.debug "updateMatchedNamespaces: ${cloud} ${updateList.size()}"
		def updates = []
		
		for(update in updateList) {
			def matchItem = update.masterItem
			def existing = update.existingItem
			Boolean save = false

			if(existing.name != matchItem.status.name) {
				existing.name = matchItem.status.name
				save = true
			}
			if(save) {
				updates << existing
			}
		}
		if(updates) {
			morpheusContext.async.cloud.pool.bulkSave(updates).blockingGet()
		}
	}

	private removeMissingResourcePools(List<CloudPoolIdentity> removeList) {
		log.debug "removeMissingResourcePools: ${removeList?.size()}"
		morpheusContext.async.cloud.pool.bulkRemove(removeList).blockingGet()
	}
}