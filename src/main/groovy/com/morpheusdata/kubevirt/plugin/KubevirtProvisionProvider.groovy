package com.morpheusdata.kubevirt.plugin

import com.morpheusdata.core.AbstractProvisionProvider
import com.morpheusdata.core.MorpheusContext
import com.morpheusdata.core.Plugin
import com.morpheusdata.core.data.DataQuery
import com.morpheusdata.core.providers.ComputeProvisionProvider
import com.morpheusdata.core.providers.HostProvisionProvider
import com.morpheusdata.core.providers.ProvisionProvider
import com.morpheusdata.core.providers.VmProvisionProvider
import com.morpheusdata.core.providers.WorkloadProvisionProvider
import com.morpheusdata.model.Cloud
import com.morpheusdata.model.ComputeServer
import com.morpheusdata.model.ComputeServerInterfaceType
import com.morpheusdata.model.ComputeServerType
import com.morpheusdata.model.ComputeTypeLayout
import com.morpheusdata.model.ComputeTypeSet
import com.morpheusdata.model.ContainerType
import com.morpheusdata.model.HostType
import com.morpheusdata.model.Icon
import com.morpheusdata.model.ImageType
import com.morpheusdata.model.Instance
import com.morpheusdata.model.OptionType
import com.morpheusdata.model.OsType
import com.morpheusdata.model.ServicePlan
import com.morpheusdata.model.VirtualImage
import com.morpheusdata.model.Workload
import com.morpheusdata.model.StorageVolume
import com.morpheusdata.model.StorageVolumeType
import com.morpheusdata.model.provisioning.HostRequest
import com.morpheusdata.model.provisioning.UserConfiguration
import com.morpheusdata.model.provisioning.WorkloadRequest
import com.morpheusdata.model.provisioning.UsersConfiguration
import com.morpheusdata.request.ResizeRequest
import com.morpheusdata.response.HostResponse
import com.morpheusdata.response.ServiceResponse
import com.morpheusdata.response.WorkloadResponse
import com.morpheusdata.response.ProvisionResponse
import com.morpheusdata.response.PrepareWorkloadResponse
import groovy.json.JsonOutput
import groovy.util.logging.Slf4j
import org.apache.http.client.methods.HttpDelete
import org.apache.http.client.methods.HttpGet
import org.apache.http.client.methods.HttpPost
import org.apache.http.entity.StringEntity

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
import io.fabric8.kubernetes.client.utils.Serialization;

@Slf4j
class KubevirtProvisionProvider extends AbstractProvisionProvider implements ComputeProvisionProvider, VmProvisionProvider, WorkloadProvisionProvider.ResizeFacet, HostProvisionProvider.ResizeFacet {
	KubevirtPlugin plugin
	MorpheusContext context
	private Map authConfig
	private static final String UBUNTU_VIRTUAL_IMAGE_CODE = 'kubevirt.image.morpheus.ubuntu.18.04'


	KubevirtProvisionProvider(KubevirtPlugin plugin, MorpheusContext context) {
		this.plugin = plugin
		this.context = context
	}

	@Override
	Icon getCircularIcon() {
		return new Icon(path:"kubevirt-circular.svg", darkPath: "kubevirt-circular.svg")
	}

	@Override
	ServiceResponse createWorkloadResources(Workload workload, Map opts) {
		return ServiceResponse.success()
	}

	@Override
	ServiceResponse startServer(ComputeServer computeServer) {
		return ServiceResponse.success()
	}

	@Override
	ServiceResponse stopServer(ComputeServer computeServer) {
		return ServiceResponse.success()
	}

	@Override
	HostType getHostType() {
		HostType.vm
	}

	@Override
	Collection<VirtualImage> getVirtualImages() {
		VirtualImage virtualImage = new VirtualImage(
				code: UBUNTU_VIRTUAL_IMAGE_CODE,
				category:'kubevirt.image.morpheus',
				name:'Ubuntu 18.04 LTS (Kubevirt Marketplace)',
				imageType: ImageType.qcow2,
				platform: 'linux',
				systemImage:false,
				isCloudInit:false,
				installAgent: false,
				externalId:'ubuntu-18-04-x64',
				osType: new OsType(code: 'ubuntu.18.04.64')
		)
		[virtualImage]
	}

	@Override
	Collection<ComputeTypeLayout> getComputeTypeLayouts() {}

	@Override
	Collection<OptionType> getOptionTypes() {
		def options = []
		options << new OptionType([
			name: 'skip agent install',
			code: 'provisionType.kubevirt.noAgent',
			category: 'provisionType.kubevirt',
			inputType: OptionType.InputType.CHECKBOX,
			fieldName: 'noAgent',
			fieldContext: 'config',
			fieldCode: 'gomorpheus.optiontype.SkipAgentInstall',
			fieldLabel: 'Skip Agent Install',
			fieldGroup:'Advanced Options',
			displayOrder: 4,
			required: false,
			enabled: true,
			editable:false,
			global:false,
			placeHolder:null,
			helpBlock:'Skipping Agent installation will result in a lack of logging and guest operating system statistics. Automation scripts may also be adversely affected.',
			defaultValue:null,
			custom:false,
			fieldClass:null
		])
		options << new OptionType([
				name : 'kubevirt image',
				code : 'kubevirt-plugin-image',
				fieldName : 'imageId',
				fieldContext : 'config',
				fieldLabel : 'Image',
				inputType : OptionType.InputType.TEXT,
				displayOrder : 100,
				required : true,
		])

		options << new OptionType([
			name : 'kubevirtHost',
			code : 'kubevirt-provision-host',
			fieldName : 'hostName',
			fieldContext : 'config',
			fieldLabel : 'Host',
			required : false,
			inputType : OptionType.InputType.SELECT,
			displayOrder : 101,
			optionSource: 'kubevirtHost'

		])
		options << new OptionType([
				name : 'kubevirt network config',
				code : 'kubevirt-plugin-network-config',
				fieldName : 'networkConfigId',
				fieldContext : 'config',
				fieldLabel : 'Network Config',
				inputType : OptionType.InputType.SELECT,
				displayOrder : 110,
				required : true,
				optionSource : 'kubevirtPluginNetwork'		
		])
		options
	}

	@Override
	Collection<OptionType> getNodeOptionTypes() {
		OptionType logFolder = new OptionType([
			name : 'mountLogs',
			code : 'kubevirt-node-log-folder',
			fieldName : 'mountLogs',
			fieldContext : 'domain',
			fieldLabel : 'Log Folder',
			inputType : OptionType.InputType.TEXT,
			displayOrder : 101,
			required : false,
		])
		OptionType configFolder = new OptionType([
			name : 'mountConfig',
			code : 'kubevirt-node-config-folder',
			fieldName : 'mountConfig',
			fieldContext : 'domain',
			fieldLabel : 'Config Folder',
			inputType : OptionType.InputType.TEXT,
			displayOrder : 102,
			required : false,
		])
		OptionType deployFolder = new OptionType([
			name : 'mountData',
			code : 'kubevirt-node-deploy-folder',
			fieldName : 'mountData',
			fieldContext : 'domain',
			fieldLabel : 'Deploy Folder',
			inputType : OptionType.InputType.TEXT,
			displayOrder : 103,
			helpText: '(Optional) If using deployment services, this mount point will be replaced with the contents of said deployments.',
			required : false,
		])
		OptionType checkTypeCode = new OptionType([
			name : 'checkTypeCode',
			code : 'kubevirt-node-check-type-code',
			fieldName : 'checkTypeCode',
			fieldContext : 'domain',
			fieldLabel : 'Check Type Code',
			inputType : OptionType.InputType.HIDDEN,
			defaultValue: 'vmCheck',
			displayOrder : 104,
			required : false,
		])
		OptionType statTypeCode = new OptionType([
			name : 'statTypeCode',
			code : 'kubevirt-node-stat-type-code',
			fieldName : 'statTypeCode',
			fieldContext : 'domain',
			fieldLabel : 'Stat Type Code',
			inputType : OptionType.InputType.HIDDEN,
			defaultValue: 'vm',
			displayOrder : 105,
			required : false,
		])
		OptionType logTypeCode = new OptionType([
			name : 'logTypeCode',
			code : 'kubevirt-node-log-type-code',
			fieldName : 'logTypeCode',
			fieldContext : 'domain',
			fieldLabel : 'Log Type Code',
			inputType : OptionType.InputType.HIDDEN,
			defaultValue: 'vm',
			displayOrder : 106,
			required : false,
		])
		OptionType showServerLogs = new OptionType([
			name : 'showServerLogs',
			code : 'kubevirt-node-show-server-logs',
			fieldName : 'showServerLogs',
			fieldContext : 'domain',
			fieldLabel : 'Show Server Logs',
			inputType : OptionType.InputType.HIDDEN,
			defaultValue: true,
			displayOrder : 107,
			required : false,
		])
		return [logFolder, configFolder, deployFolder, checkTypeCode, statTypeCode, showServerLogs, logTypeCode]
	}

	@Override
	Collection<ServicePlan> getServicePlans() {
		def servicePlans = []
		def servicePlanConfig = [
				code:'kubevirt-512',
				editable:true,
				name:'1 CPU, 512MB Memory',
				description:'1 CPU, 512MB Memory',
				sortOrder:0,
				maxStorage:10l * 1024l * 1024l * 1024l,
				maxMemory:1l * 512l * 1024l * 1024l, maxCpu:0,
				maxCores:1,
				customMaxStorage:true,
				customMaxDataStorage:true,
				addVolumes:true,
				coresPerSocket:1
		]
		servicePlans << new ServicePlan(servicePlanConfig)
		servicePlanConfig = [
				code:'kubevirt-1024',
				editable:true,
				name:'1 CPU, 1GB Memory',
				description:'1 CPU, 1GB Memory',
				sortOrder:1,
				maxStorage:10l * 1024l * 1024l * 1024l,
				maxMemory:1l * 1024l * 1024l * 1024l,
				maxCpu:0,
				maxCores:1,
				customMaxStorage:true,
				customMaxDataStorage:true,
				addVolumes:true,
				coresPerSocket:1
		]
		servicePlans << new ServicePlan(servicePlanConfig)
		servicePlanConfig = [
				code:'kubevirt-2048',
				editable:true,
				name:'1 CPU, 2GB Memory',
				description:'1 CPU, 2GB Memory',
				sortOrder:2,
				maxStorage:20l * 1024l * 1024l * 1024l,
				maxMemory:2l * 1024l * 1024l * 1024l,
				maxCpu:0,
				maxCores:1,
				customMaxStorage:true,
				customMaxDataStorage:true,
				addVolumes:true,
				coresPerSocket:1
		]
		servicePlans << new ServicePlan(servicePlanConfig)
		servicePlanConfig = [
				code:'kubevirt-4096',
				editable:true,
				name:'1 CPU, 4GB Memory',
				description:'1 CPU, 4GB Memory',
				sortOrder:3,
				maxStorage:40l * 1024l * 1024l * 1024l,
				maxMemory:4l * 1024l * 1024l * 1024l,
				maxCpu:0,
				maxCores:1,
				customMaxStorage:true,
				customMaxDataStorage:true,
				addVolumes:true,
				coresPerSocket:1
		]
		servicePlans << new ServicePlan(servicePlanConfig)
		servicePlanConfig = [
				code:'kubevirt-8192',
				editable:true,
				name:'2 CPU, 8GB Memory',
				description:'2 CPU, 8GB Memory',
				sortOrder:4,
				maxStorage:80l * 1024l * 1024l * 1024l,
				maxMemory:8l * 1024l * 1024l * 1024l,
				maxCpu:0, maxCores:2,
				customMaxStorage:true,
				customMaxDataStorage:true,
				addVolumes:true,
				coresPerSocket:1
		]
		servicePlans << new ServicePlan(servicePlanConfig)
		servicePlanConfig = [
				code:'kubevirt-16384',
				editable:true,
				name:'2 CPU, 16GB Memory',
				description:'2 CPU, 16GB Memory',
				sortOrder:5,
				maxStorage:160l * 1024l * 1024l * 1024l,
				maxMemory:16l * 1024l * 1024l * 1024l,
				maxCpu:0,
				maxCores:2,
				customMaxStorage:true,
				customMaxDataStorage:true,
				addVolumes:true,
				coresPerSocket:1
		]
		servicePlans << new ServicePlan(servicePlanConfig)
		servicePlanConfig = [
				code:'kubevirt-24576',
				editable:true,
				name:'4 CPU, 24GB Memory',
				description:'4 CPU, 24GB Memory',
				sortOrder:6,
				maxStorage:240l * 1024l * 1024l * 1024l,
				maxMemory:24l * 1024l * 1024l * 1024l,
				maxCpu:0,
				maxCores:4,
				customMaxStorage:true,
				customMaxDataStorage:true,
				addVolumes:true,
				coresPerSocket:1
		]
		servicePlans << new ServicePlan(servicePlanConfig)
		servicePlanConfig = [
				code:'kubevirt-32768',
				editable:true,
				name:'4 CPU, 32GB Memory',
				description:'4 CPU, 32GB Memory',
				sortOrder:7,
				maxStorage:320l * 1024l * 1024l * 1024l,
				maxMemory:32l * 1024l * 1024l * 1024l,
				maxCpu:0,
				maxCores:4,
				customMaxStorage:true,
				customMaxDataStorage:true,
				addVolumes:true,
				coresPerSocket:1
		]
		servicePlans << new ServicePlan(servicePlanConfig)
		servicePlanConfig = [
				code:'kubevirt-internal-custom',
				editable:false,
				name:'Custom Kubevirt',
				description:'Custom Kubevirt',
				sortOrder:0,
				customMaxStorage:true,
				customMaxDataStorage:true,
				addVolumes:true,
				customCpu:true,
				customCores:true,
				customMaxMemory:true,
				deletable:false,
				provisionable:false,
				maxStorage:0l,
				maxMemory:0l,
				maxCpu:0,
				maxCores:1,
				coresPerSocket:0
		]
		servicePlans << new ServicePlan(servicePlanConfig)
		return servicePlans
	}


	@Override
	Collection<ComputeServerInterfaceType> getComputeServerInterfaceTypes() {
		def computeServerInterfaceTypes = []
		def config = [
				code:'kubevirt-plugin-ne2k_pci',
				externalId:'ne2k_pci',
				name:'Kubevirt Plugin ne2k_pci',
				defaultType: false,
				enabled: true,
				displayOrder:4
		]
		computeServerInterfaceTypes << new ComputeServerInterfaceType(config)
		config = [
				code:'kubevirt-plugin-e1000e',
				externalId:'e1000e',
				name:'Kubevirt Plugin e1000e',
				defaultType: false,
				enabled: true,
				displayOrder:3
		]
		computeServerInterfaceTypes << new ComputeServerInterfaceType(config)
		config = [
				code:'kubevirt-plugin-e1000',
				externalId:'e1000',
				name:'Kubevirt Plugin e1000',
				defaultType: false,
				enabled: true,
				displayOrder:2
		]
		computeServerInterfaceTypes << new ComputeServerInterfaceType(config)
		config = [
				code:'kubevirt-plugin-virtio',
				externalId:'virtio',
				name:'Kubevirt Plugin virtio',
				defaultType: true,
				enabled: true,
				displayOrder:1
		]
		computeServerInterfaceTypes << new ComputeServerInterfaceType(config)
		return computeServerInterfaceTypes
	}

	@Override
	Collection<StorageVolumeType> getDataVolumeStorageTypes() {
		getStorageVolumeTypes()
	}

	private getStorageVolumeTypes() {
		def volumeTypes = []

		volumeTypes << new StorageVolumeType([
			code: 'kubevirt-persistent-disk',
			externalId: 'persistent',
			name: 'persistent',
			displayOrder: 0
		])

		volumeTypes << new StorageVolumeType([
			code: 'kubevirt-empty-disk',
			externalId: 'empty',
			name: 'empty',
			displayOrder: 1
		])

		volumeTypes
	}

	@Override
	String getCode() {
		return 'kubevirt'
	}

	@Override
	String getName() {
		return 'Kubevirt'
	}

	@Override
	Boolean hasDatastores() {
		return false
	}

	@Override
	Boolean hasPlanTagMatch() {
		false
	}

	@Override
	Integer getMaxNetworks() {
		return 5
	}

	@Override
	Boolean createDefaultInstanceType() {
		return true
	}

	@Override
	String getDeployTargetService() {
		return "vmDeployTargetService"
	}

	@Override
	String getNodeFormat() {
		return "vm"
	}

	@Override
	Boolean hasCloneTemplate() {
		return true
	}

	@Override
	ServiceResponse validateWorkload(Map opts) {
		log.debug "validateWorkload: ${opts}"
		return ServiceResponse.success()
	}

	@Override
	ServiceResponse validateHost(ComputeServer server, Map opts) {
		log.debug("validateDockerHost, server: ${server}, opts: ${opts}")
		return ServiceResponse.success()
	}


	@Override
	ServiceResponse<PrepareWorkloadResponse> prepareWorkload(Workload workload, WorkloadRequest workloadRequest, Map opts) {
		ServiceResponse<PrepareWorkloadResponse> resp = new ServiceResponse<>()
		resp.data = new PrepareWorkloadResponse(workload: workload, options: [sendIp: false])
		log.info "prepareWorkload: ${workload} ${workloadRequest} ${opts}"
		ComputeServer server = workload.server
		try {
			VirtualImage virtualImage
			/*Long computeTypeSetId = server.typeSet?.id
			if(computeTypeSetId) {
				ComputeTypeSet computeTypeSet = morpheus.computeTypeSet.get(computeTypeSetId).blockingGet()
				if(computeTypeSet.containerType) {
					ContainerType containerType = morpheus.containerType.get(computeTypeSet.containerType.id).blockingGet()
					virtualImage = containerType.virtualImage
				}
			}*/
			log.info "Finding Virtual Image"
			virtualImage = morpheus.services.virtualImage.find(new DataQuery().withFilter("name", "kubedemo"))
			if(!virtualImage) {
				resp.msg = "No virtual image selected"
			} else {
				workload.server.sourceImage = virtualImage
				//saveAndGet(workload)
				resp.success = true
			}
		} catch(e) {
			resp.msg = "Error in prepareWorkload: ${e}"
			log.error "${resp.msg}, ${e}", e

		}
		return resp
	}

	@Override
	ServiceResponse<ProvisionResponse> runWorkload(Workload workload, WorkloadRequest workloadRequest, Map opts) {
		log.info "Kubevirt Provision Provider: runWorkload ${workload.configs} ${opts}"
		def containerConfig = new groovy.json.JsonSlurper().parseText(workload.configs ?: '{}')
		log.info "Workload Name: ${workload.instance.name}"
		log.info "Workload Hostname: ${workload.instance.hostName}"
		log.info "Workload Tags: ${workload.instance.tags}"
		log.info "Workload Metadata: ${workload.instance.metadata}"
		log.info "Workload Server Tags: ${workload.server.tags}"
		log.info "Workload Server Metadata: ${workload.server.metadata}"
		def tags = []
		for(item in workload.instance.metadata){
			def tag = [:]
			tag["name"] = item.name
			tag["value"] = item.value
			log.info "Tag: name - ${item.name} value - ${item.value}"
			tags << tag
		}
		ComputeServer server = workload.server
		Cloud cloud = server?.cloud
		ProvisionResponse provisionResponse = new ProvisionResponse(success: true)
		this.authConfig = plugin.getAuthConfig(cloud)
		ConfigBuilder configBuilder = new ConfigBuilder();
		Config config = configBuilder
			.withMasterUrl(authConfig.apiUrl)
			.withTrustCerts(true)
			.withOauthToken(cloud.configMap.serviceToken)
			.build();
		OkHttpClientFactory factory = new OkHttpClientFactory()
		KubernetesClient client = new KubernetesClientBuilder().withConfig(config).withHttpClientFactory(factory).build();
		ResourceDefinitionContext context = new ResourceDefinitionContext.Builder()
			.withGroup("kubevirt.io")
			.withVersion("v1")
			.withKind("VirtualMachineInstance")
			.withPlural("virtualmachineinstances")
			.withNamespaced(true)
			.build();

		def maxCores = workload.maxCores ?: workload.instance.plan.maxCores ?: 1

		GenericKubernetesResource vmi = createVirtualMachineInstance(workload.instance.name, maxCores, tags);
		log.info "VMI RESOURCE: ${vmi}"
		//String rawJsonCustomResourceObj = "{\"apiVersion\":\"kubevirt.io/v1\",\"kind\":\"VirtualMachineInstance\",\"metadata\":{\"name\":\"testvmi-nocloud\"},\"spec\":{\"terminationGracePeriodSeconds\":30,\"domain\":{\"resources\":{\"requests\":{\"memory\":\"1024M\"}},\"devices\":{\"disks\":[{\"name\":\"containerdisk\",\"disk\":{\"bus\":\"virtio\"}},{\"name\":\"emptydisk\",\"disk\":{\"bus\":\"virtio\"}},{\"disk\":{\"bus\":\"virtio\"},\"name\":\"cloudinitdisk\"}]}},\"volumes\":[{\"name\":\"containerdisk\",\"containerDisk\":{\"image\":\"kubevirt/fedora-cloud-container-disk-demo:latest\"}},{\"name\":\"emptydisk\",\"emptyDisk\":{\"capacity\":\"2Gi\"}},{\"name\":\"cloudinitdisk\",\"cloudInitNoCloud\":{\"userData\":\"#cloud-config\npassword: fedora\nchpasswd: { expire: False }\"}}]}}";
		GenericKubernetesResource object = client.genericKubernetesResources(context).inNamespace("default").create(vmi);

		provisionResponse.installAgent = false
		provisionResponse.noAgent = true
		return new ServiceResponse<ProvisionResponse>(success: true, data: provisionResponse)
	}

	@Override
	ServiceResponse prepareHost(ComputeServer server, HostRequest hostRequest, Map opts) {
		log.debug "prepareHost: ${server} ${hostRequest} ${opts}"

		def rtn = [success: false, msg: null]
		try {
			VirtualImage virtualImage
			/*Long computeTypeSetId = server.typeSet?.id
			if(computeTypeSetId) {
				ComputeTypeSet computeTypeSet = morpheus.computeTypeSet.get(computeTypeSetId).blockingGet()
				if(computeTypeSet.containerType) {
					ContainerType containerType = morpheus.containerType.get(computeTypeSet.containerType.id).blockingGet()
					virtualImage = containerType.virtualImage
				}
			}*/
			log.info "Finding Virtual Image"
			virtualImage = morpheus.services.virtualImage.find(new DataQuery().withFilter("code", "kubevirt.image.morpheus.ubuntu.18.04"))
			if(!virtualImage) {
				rtn.msg = "No virtual image selected"
			} else {
				server.sourceImage = virtualImage
				saveAndGet(server)
				rtn.success = true
			}
		} catch(e) {
			rtn.msg = "Error in prepareHost: ${e}"
			log.error "${rtn.msg}, ${e}", e

		}
		new ServiceResponse(rtn.success, rtn.msg, null, null)
	}

	@Override
	ServiceResponse finalizeWorkload(Workload workload) {
		log.info("finalizeWorkload: ${workload?.id}")
		def rtn = [success: true, msg: null]
		new ServiceResponse(rtn.success, rtn.msg, null, null)
	}

	@Override
	ServiceResponse<HostResponse> runHost(ComputeServer server, HostRequest hostRequest, Map opts) {
		log.info "runHost: ${server} ${hostRequest} ${opts}"
        return ServiceResponse.success()
	}

	@Override
	ServiceResponse<HostResponse> waitForHost(ComputeServer server) {
		log.debug "waitForHost: ${server}"
        return ServiceResponse.success()
	}

	@Override
	ServiceResponse finalizeHost(ComputeServer server) {
		log.debug "finalizeHost: ${server} "
		return ServiceResponse.success();
	}

	@Override
	ServiceResponse resizeServer(ComputeServer server, ResizeRequest resizeRequest, Map opts) {
		log.debug "resizeServer: ${server} ${resizeRequest} ${opts}"
		internalResizeServer(server, resizeRequest)
	}

	@Override
	ServiceResponse resizeWorkload(Instance instance, Workload workload, ResizeRequest resizeRequest, Map opts) {
		log.debug "resizeWorkload: ${instance} ${workload} ${resizeRequest} ${opts}"
        return ServiceResponse.success()
	}

	private ServiceResponse internalResizeServer(ComputeServer server, ResizeRequest resizeRequest) {
		log.debug "internalResizeServer: ${server} ${resizeRequest}"
		ServiceResponse rtn = ServiceResponse.success()
		rtn
	}

	@Override
	ServiceResponse<WorkloadResponse> stopWorkload(Workload workload) {
        return ServiceResponse.success()
	}

	@Override
	ServiceResponse<WorkloadResponse> startWorkload(Workload workload) {
        return ServiceResponse.success()
	}

	@Override
	ServiceResponse restartWorkload(Workload workload) {
		log.debug 'restartWorkload'
        return ServiceResponse.success()
	}

	@Override
	ServiceResponse removeWorkload(Workload workload, Map opts) {
		log.info "removeWorkload: ${workload} ${opts}"
		ComputeServer server = workload.server
		Cloud cloud = server?.cloud
		this.authConfig = plugin.getAuthConfig(cloud)
		ConfigBuilder configBuilder = new ConfigBuilder();
		Config config = configBuilder
			.withMasterUrl(authConfig.apiUrl)
			.withTrustCerts(true)
			.withOauthToken(cloud.configMap.serviceToken)
			.build();
		OkHttpClientFactory factory = new OkHttpClientFactory()
		KubernetesClient client = new KubernetesClientBuilder().withConfig(config).withHttpClientFactory(factory).build();
		ResourceDefinitionContext context = new ResourceDefinitionContext.Builder()
			.withGroup("kubevirt.io")
			.withVersion("v1")
			.withKind("VirtualMachineInstance")
			.withPlural("virtualmachineinstances")
			.withNamespaced(true)
			.build();		


		client.genericKubernetesResources(context).inNamespace("default").withName(workload.instance.name).delete()
        return ServiceResponse.success()
	}

	@Override
	MorpheusContext getMorpheus() {
		return this.context
	}

	@Override
	Plugin getPlugin() {
		return this.plugin
	}

	@Override
	Boolean canAddVolumes() {
		true
	}

	@Override
	Boolean canCustomizeRootVolume() {
		return false
	}

	@Override
	Boolean canResizeRootVolume() {
		return false
	}

	@Override
	Boolean canCustomizeDataVolumes() {
		return true
	}

	@Override
	Boolean hasComputeZonePools() {
		return true
	}

	@Override
	Boolean hasNetworks() {
		true
	}

	@Override
	Boolean canReconfigureNetwork() {
		true
	}

	@Override
	ServiceResponse<ProvisionResponse> getServerDetails(ComputeServer server) {
		ProvisionResponse rtn = new ProvisionResponse()
		//ComputeServer server = workload.server
		sleep(20000)
		Cloud cloud = server?.cloud
		this.authConfig = plugin.getAuthConfig(cloud)
		ConfigBuilder configBuilder = new ConfigBuilder();
		Config config = configBuilder
			.withMasterUrl(authConfig.apiUrl)
			.withTrustCerts(true)
			.withOauthToken(cloud.configMap.serviceToken)
			.build();
		OkHttpClientFactory factory = new OkHttpClientFactory()
		KubernetesClient client = new KubernetesClientBuilder().withConfig(config).withHttpClientFactory(factory).build();
		ResourceDefinitionContext context = new ResourceDefinitionContext.Builder()
			.withGroup("kubevirt.io")
			.withVersion("v1")
			.withKind("VirtualMachineInstance")
			.withPlural("virtualmachineinstances")
			.withNamespaced(true)
			.build();		


		GenericKubernetesResource customResourceObject = client.genericKubernetesResources(context).inNamespace("default").withName(server.name).get()
		log.info "VMI Object: ${customResourceObject}"
		String ipAddr = customResourceObject.get("status","interfaces",0,"ipAddress")
		ObjectMeta metadata = customResourceObject.getMetadata();
        final String name = metadata.getName();
		log.info "VMI Object IP: ${ipAddr}"
		rtn.externalId = name
		rtn.success = true
		rtn.publicIp = ipAddr
		rtn.privateIp = ipAddr
		rtn.hostname = name
		return ServiceResponse.success(rtn)
	}

	ServiceResponse<WorkloadResponse> serverStatus(ComputeServer server) {
		log.debug "check server status for server ${server.externalId}"
        return ServiceResponse.success()
	}

	ServiceResponse<WorkloadResponse> powerOffServer(String apiKey, String dropletId) {
		log.debug "power off server"
        return ServiceResponse.success()
	}

	protected ComputeServer saveAndGet(ComputeServer server) {
		morpheus.computeServer.save([server]).blockingGet()
		return morpheus.computeServer.get(server.id).blockingGet()
	}

	protected cleanInstanceName(name) {
		def rtn = name.replaceAll(/[^a-zA-Z0-9\.\-]/,'')
		return rtn
	}

	private GenericKubernetesResource createVirtualMachineInstance(name, maxCores, tags) {
		Map<String, Object> crAsMap = new HashMap<>();
		crAsMap.put("apiVersion", "kubevirt.io/v1");
		crAsMap.put("kind", "VirtualMachineInstance");

		Map<String, Object> crMetadata = new HashMap<>();
		crMetadata.put("name", name);
		//crMetadata.put("labels", tags);
		log.info "Labels Definition ${tags}"
		Map<String, Object> crLabels = new HashMap<>();
		for(tag in tags){
			crLabels.put(tag.name, tag.value);
		}

		crMetadata.put("labels", crLabels);

		Map<String, Object> crSpec = new HashMap<>();
		crSpec.put("terminationGracePeriodSeconds", 30);

		Map<String, Object> crSpecDomain = new HashMap<>();
		crSpec.put("domain",crSpecDomain)

		Map<String, Object> crSpecDomainResources = new HashMap<>();
		crSpecDomain.put("resources",crSpecDomainResources)

		Map<String, Object> crSpecDomainDevices = new HashMap<>();
		crSpecDomain.put("devices",crSpecDomainDevices)

		Map<String, Object> crSpecDomainResourceRequests = new HashMap<>();
		crSpecDomainResources.put("requests",crSpecDomainResourceRequests)
		crSpecDomainResourceRequests.put("memory","1024M")

		def disks = []
		def disk1 = [:]
		disk1["name"] = "emptydisk"
		def disk1Config = [:]
		disk1Config["bus"] = "virtio"
		disk1["disk"] = disk1Config
		disks << disk1

		crSpecDomainDevices.put("disks",disks)

		def volumes = []
		def vol1 = [:]
		vol1["name"] = "emptydisk"
		def vol1Config = [:]
		vol1Config["capacity"] = "2Gi"
		vol1["emptyDisk"] = vol1Config
		volumes << vol1
		crSpec.put("volumes", volumes);
		crAsMap.put("metadata", crMetadata);
		crAsMap.put("spec", crSpec);

		log.info "VMI JSON PAYLOAD: ${crAsMap}"

		return Serialization.jsonMapper().convertValue(crAsMap, GenericKubernetesResource.class);
  }
}