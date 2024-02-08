package com.morpheusdata.kubevirt.plugin

import com.morpheusdata.core.backup.BackupProvider
import com.morpheusdata.core.providers.CloudProvider
import com.morpheusdata.core.MorpheusContext
import com.morpheusdata.core.Plugin
import com.morpheusdata.core.providers.ProvisionProvider
import com.morpheusdata.model.*
import com.morpheusdata.request.ValidateCloudRequest
import com.morpheusdata.response.ServiceResponse
import groovy.json.JsonOutput
import groovy.util.logging.Slf4j
import org.apache.http.client.methods.HttpDelete
import org.apache.http.client.methods.HttpGet
import org.apache.http.client.methods.HttpPost
import org.apache.http.entity.StringEntity
import com.morpheusdata.core.util.HttpApiClient
import com.morpheusdata.kubevirt.plugin.sync.HostsSync
import com.morpheusdata.kubevirt.plugin.sync.VirtualMachinesSync
import com.morpheusdata.kubevirt.plugin.sync.NamespacesSync
import com.morpheusdata.kubevirt.plugin.sync.ImagesSync

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

import java.security.MessageDigest

@Slf4j
class KubevirtCloudProvider implements CloudProvider {
	KubevirtPlugin plugin
	MorpheusContext morpheusContext

	KubevirtCloudProvider(KubevirtPlugin plugin, MorpheusContext context) {
		this.plugin = plugin
		this.morpheusContext = context
	}

	@Override
	MorpheusContext getMorpheus() {
		return this.morpheusContext
	}

	@Override
	Plugin getPlugin() {
		return this.plugin
	}

	@Override
	Icon getIcon() {
		return new Icon(path:"kubevirt-horizontal-color.svg", darkPath: "kubevirt-horizontal-color.svg")
	}

	@Override
	Icon getCircularIcon() {
		return new Icon(path:"kubevirt-plugin.svg", darkPath: "kubevirt-plugin.svg")
	}

	@Override
	String getCode() {
		return 'kubevirt-plugin'
	}

	@Override
	String getName() {
		return 'Kubevirt'
	}

	@Override
	String getDescription() {
		return 'Kubevirt Cloud'
	}

	@Override
	String getDefaultProvisionTypeCode() {
		return 'kubevirt-provision-provider'
	}

	@Override
	Boolean hasComputeZonePools() {
		return true
	}

	@Override
	Boolean hasNetworks() {
		return true
	}

	@Override
	Boolean hasBareMetal() {
		return false
	}

	@Override
	Boolean hasDatastores() {
		return false
	}

	@Override
	Boolean hasFolders() {
		return false
	}

	@Override
	Boolean hasCloudInit() {
		return false
	}

	@Override
	Boolean supportsDistributedWorker() {
		return false
	}

	@Override
	Collection<OptionType> getOptionTypes() {
		OptionType serviceUrl = new OptionType(
				name: 'Kubevirt API Url',
				code: 'kubevirt-api-url',
				fieldName: 'serviceUrl',
				displayOrder: 0,
				fieldLabel: 'Kubevirt API Url',
				required: true,
				inputType: OptionType.InputType.TEXT,
				fieldContext: 'config',
		)
		OptionType credentials = new OptionType(
				name: 'Credentials',
				code: 'kubevirt-plugin-credential',
				inputType: OptionType.InputType.CREDENTIAL,
				fieldName: 'type',
				fieldLabel: 'Credentials',
				fieldContext: 'credential',
				required: true,
				defaultValue: 'local',
				displayOrder: 1,
				optionSource: 'credentials',
				config: '{"credentialTypes":["api-key"]}'
		)
		OptionType serviceToken = new OptionType(
				name: 'API Token',
				code: 'kubevirt-api-token',
				fieldName: 'serviceToken',
				displayOrder: 2,
				fieldLabel: 'API Token',
				required: true,
				inputType: OptionType.InputType.PASSWORD,
				fieldContext: 'config',
				localCredential: true
		)
		OptionType inventoryInstances = new OptionType(
				name: 'KubevirtInventory',
				code: 'kubevirt-inventory',
				fieldName: 'importExisting',
				displayOrder: 3,
				fieldLabel: 'Inventory Existing Instances',
				required: false,
				inputType: OptionType.InputType.CHECKBOX,
				fieldContext: 'config'
		)
		return [serviceUrl,credentials,serviceToken,inventoryInstances]
	}

	@Override
	Collection<ComputeServerType> getComputeServerTypes() {
		ComputeServerType hypervisorType = new ComputeServerType()
		hypervisorType.name = 'Kubevirt Hypervisor'
		hypervisorType.code = 'kubevirt-hypervisor'
		hypervisorType.description = 'kubevirt hypervisor'
		hypervisorType.vmHypervisor = true
		hypervisorType.controlPower = false
		hypervisorType.reconfigureSupported = false
		hypervisorType.externalDelete = false
		hypervisorType.hasAutomation = false
		hypervisorType.agentType = ComputeServerType.AgentType.none
		hypervisorType.platform = PlatformType.linux
		hypervisorType.managed = false
		hypervisorType.provisionTypeCode = 'kubevirt-provision-provider'
		hypervisorType.nodeType = 'kubevirt-node'

		ComputeServerType serverType = new ComputeServerType()
		serverType.name = 'Kubevirt Server'
		serverType.code = 'kubevirt-server'
		serverType.description = 'kubevirt server'
		serverType.reconfigureSupported = false
		serverType.hasAutomation = false
		serverType.supportsConsoleKeymap = true
		serverType.platform = PlatformType.none
		serverType.managed = false
		serverType.provisionTypeCode = 'kubevirt-provision-provider'

		ComputeServerType kubevirtVm = new ComputeServerType()
		kubevirtVm.name = 'Kubevirt Linux VM'
		kubevirtVm.code = 'kubevirt-plugin-vm'
		kubevirtVm.description = ''
		kubevirtVm.controlEjectCd = true
		kubevirtVm.guestVm = true
		kubevirtVm.controlSuspend = true
		kubevirtVm.reconfigureSupported = false
		kubevirtVm.hasAutomation = true
		kubevirtVm.supportsConsoleKeymap = true
		kubevirtVm.platform = PlatformType.linux
		kubevirtVm.managed = true
		kubevirtVm.provisionTypeCode = 'kubevirt-provision-provider'

		ComputeServerType unmanagedType = new ComputeServerType()
		unmanagedType.name = 'Kubevirt Instance'
		unmanagedType.code = 'kubevirt-unmanaged'
		unmanagedType.description = 'Kubevirt Instance'
		unmanagedType.reconfigureSupported = false
		unmanagedType.hasAutomation = true
		unmanagedType.supportsConsoleKeymap = true
		unmanagedType.platform = PlatformType.linux
		unmanagedType.managed = false
		unmanagedType.provisionTypeCode = 'kubevirt-provision-provider'
		unmanagedType.nodeType = 'unmanaged'
		unmanagedType.managedServerType = 'kubevirt-vm'

		return [hypervisorType, serverType, kubevirtVm, unmanagedType]

	}

	@Override
	Collection<ProvisionProvider> getAvailableProvisionProviders() {
		return plugin.getProvidersByType(ProvisionProvider) as Collection<ProvisionProvider>
	}

	@Override
	Collection<BackupProvider> getAvailableBackupProviders() {
		return plugin.getProvidersByType(BackupProvider) as Collection<BackupProvider>
	}

	@Override
	ProvisionProvider getProvisionProvider(String providerCode) {
		return getAvailableProvisionProviders().find { it.code == providerCode }
	}

	@Override
	Collection<NetworkType> getNetworkTypes() {
		return null
	}
	
	@Override
	Collection<NetworkSubnetType> getSubnetTypes() {
		return null
	}

	@Override
	Collection<StorageVolumeType> getStorageVolumeTypes() {
		return null
	}

	@Override
	Collection<StorageControllerType> getStorageControllerTypes() {
		return null
	}

	@Override
	ServiceResponse validate(Cloud zoneInfo, ValidateCloudRequest validateCloudRequest) {
		return new ServiceResponse(success: true)
	}

	@Override
	ServiceResponse initializeCloud(Cloud cloud) {
		ServiceResponse rtn = new ServiceResponse(success: false)
		log.info "Initializing Cloud: ${cloud.code}"
		def authConfig = plugin.getAuthConfig(cloud)

		// Inventory existing virtual machines
		def doInventory = cloud.getConfigProperty('importExisting')
		Boolean createNew = false
		if(doInventory == 'on' || doInventory == 'true' || doInventory == true) {
			createNew = true
		}

		(new HostsSync(plugin, cloud)).execute()
		(new VirtualMachinesSync(plugin, cloud, createNew)).execute()
		(new ImagesSync(plugin, cloud)).execute()
		(new NamespacesSync(plugin, cloud)).execute()

		rtn = ServiceResponse.success()
		
	/*
	ResourceDefinitionContext context = new ResourceDefinitionContext.Builder()
		.withGroup("k8s.cni.cncf.io")
		.withVersion("v1")
		.withKind("NetworkAttachmentDefinition")
		.withPlural("network-attachment-definitions")
		.withNamespaced(true)
		.build();

	GenericKubernetesResourceList networks = client.genericKubernetesResources(context).inNamespace("default").list();
	log.info  "Kubernetes networks ${networks.getItems()}"
	*/
	//networks.getItems().stream().map(GenericKubernetesResource::getMetadata).map(ObjectMeta::getName).forEach(logger::info);
		return rtn
	}

	@Override
	ServiceResponse refresh(Cloud cloud) {
		return ServiceResponse.success()
	}

	@Override
	void refreshDaily(Cloud cloudInfo) {
		log.debug "daily refresh run for ${cloudInfo.code}"
	}

	@Override
	ServiceResponse deleteCloud(Cloud cloudInfo) {
		return new ServiceResponse(success: true)
	}

	@Override
	ServiceResponse startServer(ComputeServer computeServer) {
		return new ServiceResponse(success: false, msg: 'No VM provided')
	}

	@Override
	ServiceResponse stopServer(ComputeServer computeServer) {
		return new ServiceResponse(success: false, msg: 'No VM provided')
	}

	@Override
	ServiceResponse deleteServer(ComputeServer computeServer) {
		return new ServiceResponse(success: true)
	}

	KeyPair findOrUploadKeypair(String apiKey, String publicKey, String keyName) {
	}

	private ensureRegionCode(Cloud cloud) {
		def authConfig = plugin.getAuthConfig(cloud)
		def apiUrl = authConfig.apiUrl
		def regionString = "${apiUrl}"
		MessageDigest md = MessageDigest.getInstance("MD5")
		md.update(regionString.bytes)
		byte[] checksum = md.digest()
		def regionCode = checksum.encodeHex().toString()
		if (cloud.regionCode != regionCode) {
			cloud.regionCode = regionCode
			morpheusContext.async.cloud.save(cloud).blockingGet()
		}
	}
}