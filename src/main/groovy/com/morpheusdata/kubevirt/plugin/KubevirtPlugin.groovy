package com.morpheusdata.kubevirt.plugin

import com.morpheusdata.core.MorpheusContext
import com.morpheusdata.core.Plugin
import com.morpheusdata.model.AccountCredential
import com.morpheusdata.model.Cloud
import groovy.util.logging.Slf4j

@Slf4j
class KubevirtPlugin extends Plugin {

	private String cloudProviderCode

	@Override
	String getCode() {
		return 'kubevirt'
	}

	@Override
	void initialize() {
		this.setName("Kubevirt")
		def kubevirtProvision = new KubevirtProvisionProvider(this, this.morpheus)
		def kubevirtCloud = new KubevirtCloudProvider(this, this.morpheus)
		cloudProviderCode = kubevirtCloud.code
		def kubevirtOptionSourceProvider = new KubevirtOptionSourceProvider(this, this.morpheus)

		this.pluginProviders.put(kubevirtProvision.code, kubevirtProvision)
		this.pluginProviders.put(kubevirtCloud.code, kubevirtCloud)
		this.pluginProviders.put(kubevirtOptionSourceProvider.code, kubevirtOptionSourceProvider)
	}

	@Override
	void onDestroy() {
	}

	MorpheusContext getMorpheusContext() {
		return morpheus
	}

	def getAuthConfig(Cloud cloud) {
		def rtn = [:]

		if(!cloud.accountCredentialLoaded) {
			AccountCredential accountCredential
			try {
				accountCredential = this.morpheus.cloud.loadCredentials(cloud.id).blockingGet()
			} catch(e) {
				// If there is no credential on the cloud, then this will error
				// TODO: Change to using 'maybe' rather than 'blockingGet'?
			}
			cloud.accountCredentialLoaded = true
			cloud.accountCredentialData = accountCredential?.data
		}

		def apiToken
		if(cloud.accountCredentialData && cloud.accountCredentialData.containsKey('password')) {
			apiToken = cloud.accountCredentialData['password']
		} else {
			apiToken = cloud.configMap.serviceToken
		}

		rtn.apiUrl = cloud.configMap.serviceUrl
		rtn.apiToken = cloud.configMap.serviceToken
		return rtn
	}

	def KubevirtCloudProvider getCloudProvider() {
		this.getProviderByCode(cloudProviderCode)
	}
}
