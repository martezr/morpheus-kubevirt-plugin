package com.morpheusdata.kubevirt.plugin

import com.morpheusdata.core.AbstractOptionSourceProvider
import com.morpheusdata.core.MorpheusContext
import com.morpheusdata.core.Plugin
import com.morpheusdata.model.*
import groovy.util.logging.Slf4j

@Slf4j
class KubevirtOptionSourceProvider extends AbstractOptionSourceProvider {

	KubevirtPlugin plugin
	MorpheusContext morpheusContext

	KubevirtOptionSourceProvider(KubevirtPlugin plugin, MorpheusContext context) {
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
	String getCode() {
		return 'kubevirt-option-source-plugin'
	}

	@Override
	String getName() {
		return 'Kubevirt Option Source Plugin'
	}

	@Override
	List<String> getMethodNames() {
		return new ArrayList<String>(['kubevirtHost','kubevirtPluginNetwork'])
	}

	def kubevirtPluginNetwork(args) {
		log.debug "kubevirtPluginNetworks: ${args}"
		return [[name: 'bridge',value:'bridge'],[name: 'masquerade',value:'masquerade']]
	}

	def kubevirtHost(args){
		def cloudId = getCloudId(args)
		if(cloudId) {
			Cloud tmpCloud = morpheusContext.cloud.getCloudById(cloudId).blockingGet()
			def options = morpheusContext.computeServer.listSyncProjections(tmpCloud.id).filter { it.category == "kubevirt.host.${tmpCloud.id}" }.map {[name: it.name, value: it.externalId]}.toSortedList {it.name}.blockingGet()
			return options
		} else {
			return []
		}
	}

	private static getCloudId(args) {
		def cloudId = null
		if(args?.size() > 0) {
			def firstArg =  args.getAt(0)
			if(firstArg?.zoneId) {
				cloudId = firstArg.zoneId.toLong()
				return cloudId
			}
			if(firstArg?.domain?.zone?.id) {
				cloudId = firstArg.domain.zone.id.toLong()
				return cloudId
			}
		}
		return cloudId
	}
}