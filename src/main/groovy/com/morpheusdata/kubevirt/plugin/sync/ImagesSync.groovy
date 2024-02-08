package com.morpheusdata.kubevirt.plugin.sync

import com.morpheusdata.core.MorpheusContext
import com.morpheusdata.core.util.ComputeUtility
import com.morpheusdata.core.util.HttpApiClient
import com.morpheusdata.core.util.SyncTask
import com.morpheusdata.model.Account
import com.morpheusdata.model.Cloud
import com.morpheusdata.model.ComputeZonePool
import com.morpheusdata.core.data.DataFilter
import com.morpheusdata.core.data.DataOrFilter
import com.morpheusdata.core.data.DataQuery
import com.morpheusdata.model.Datastore
import com.morpheusdata.model.ImageType
import com.morpheusdata.model.Network
import com.morpheusdata.model.StorageController
import com.morpheusdata.model.StorageControllerType
import com.morpheusdata.model.StorageVolume
import com.morpheusdata.model.StorageVolumeType
import com.morpheusdata.model.VirtualImage
import com.morpheusdata.model.VirtualImageLocation
import com.morpheusdata.model.projection.ComputeZonePoolIdentityProjection
import com.morpheusdata.model.projection.NetworkIdentityProjection
import com.morpheusdata.model.projection.StorageControllerIdentityProjection
import com.morpheusdata.model.projection.VirtualImageIdentityProjection
import com.morpheusdata.model.projection.VirtualImageLocationIdentityProjection
import com.morpheusdata.kubevirt.plugin.KubevirtPlugin
import groovy.util.logging.Slf4j
import io.reactivex.rxjava3.core.Observable

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
class ImagesSync {

	private Cloud cloud
	private MorpheusContext morpheusContext
	private KubevirtPlugin plugin
	private Map authConfig

	public ImagesSync(KubevirtPlugin kubevirtPlugin, Cloud cloud) {
		this.plugin = kubevirtPlugin
		this.cloud = cloud
		this.morpheusContext = kubevirtPlugin.morpheusContext
	}

	def execute() {
		log.info "BEGIN: execute ImagesSync: ${cloud.id}"
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
            ResourceDefinitionContext context = new ResourceDefinitionContext.Builder()
                .withGroup("cdi.kubevirt.io")
                .withVersion("v1beta1")
                .withKind("DataVolume")
                .withPlural("datavolumes")
                .withNamespaced(true)
                .build();

            GenericKubernetesResourceList dataVolumes = client.genericKubernetesResources(context).inNamespace("default").list();
            log.info  "Kubernetes data volumes ${dataVolumes.getItems()}"
      		List<GenericKubernetesResource> items = dataVolumes.getItems();
			def cloudItems = []
			for (GenericKubernetesResource customResource : items) {
				def imageinfo = [:]
				ObjectMeta metadata = customResource.getMetadata();
        		final String name = metadata.getName();
				def uid = customResource.get("metadata","uid")
				imageinfo["name"] = name
				imageinfo["uid"] = uid
				cloudItems << imageinfo
			}
			Observable<VirtualImageLocationIdentityProjection> existingRecords = morpheusContext.async.virtualImage.location.listIdentityProjections(
				new DataQuery().withFilters(
					new DataFilter<String>("refType", "ComputeZone"),
					new DataFilter<String>("refId", cloud.id)
				)
			)
			SyncTask<VirtualImageLocationIdentityProjection, Map, ComputeZonePool> syncTask = new SyncTask<>(existingRecords, cloudItems)
			syncTask.addMatchFunction { VirtualImageLocationIdentityProjection domainObject, Map cloudItem ->
				domainObject.externalId == cloudItem?.uid
			}.withLoadObjectDetails { List<SyncTask.UpdateItemDto<VirtualImageLocationIdentityProjection, Map>> updateItems ->
				Map<Long, SyncTask.UpdateItemDto<VirtualImageLocationIdentityProjection, Map>> updateItemMap = updateItems.collectEntries { [(it.existingItem.id): it] }
				morpheusContext.virtualImage.location.listById(updateItems?.collect { it.existingItem.id }).map { VirtualImageLocation virtualImageLocation ->
					SyncTask.UpdateItemDto<VirtualImageLocationIdentityProjection, Map> matchItem = updateItemMap[virtualImageLocation.id]
					return new SyncTask.UpdateItem<Datastore, Map>(existingItem: virtualImageLocation, masterItem: matchItem.masterItem)
				}
			}.onAdd { itemsToAdd ->
				addMissingVirtualImageLocations(itemsToAdd)
			}.onUpdate { List<SyncTask.UpdateItem<VirtualImageLocation, Map>> updateItems ->
				updateMatchedVirtualImageLocations(updateItems)
			}.onDelete { removeItems ->
				removeMissingVirtualImageLocations(removeItems)
			}.start()
		} catch(e) {
			log.error "Error in execute of ImagesSync: ${e}", e
		}
		log.debug "END: execute ImagesSync: ${cloud.id}"
	}

	def addMissingVirtualImageLocations(List objList) {
		log.debug "addMissingVirtualImageLocations: ${objList?.size()}"

		def names = objList.collect{it.status.name}?.unique()
		List<VirtualImageIdentityProjection> existingItems = []
		def allowedImageTypes = ['qcow2']

		def uniqueIds = [] as Set
		Observable domainRecords = morpheusContext.virtualImage.listSyncProjections(cloud.id).filter { VirtualImageIdentityProjection proj ->
			def include = proj.imageType in allowedImageTypes && proj.name in names && (proj.systemImage || (!proj.ownerId || proj.ownerId == cloud.owner.id))
			if(include) {
				def uniqueKey = "${proj.imageType.toString()}:${proj.name}".toString()
				if(!uniqueIds.contains(uniqueKey)) {
					uniqueIds << uniqueKey
					return true
				}
			}
			return false
		}
		SyncTask<VirtualImageIdentityProjection, Map, VirtualImage> syncTask = new SyncTask<>(domainRecords, objList)
		syncTask.addMatchFunction { VirtualImageIdentityProjection domainObject, Map cloudItem ->
			domainObject.name == cloudItem.status.name
		}.withLoadObjectDetails { List<SyncTask.UpdateItemDto<VirtualImageIdentityProjection, Map>> updateItems ->
			Map<Long, SyncTask.UpdateItemDto<VirtualImageIdentityProjection, Map>> updateItemMap = updateItems.collectEntries { [(it.existingItem.id): it] }
			morpheusContext.virtualImage.listById(updateItems?.collect { it.existingItem.id }).map { VirtualImage virtualImage ->
				SyncTask.UpdateItemDto<VirtualImageIdentityProjection, Map> matchItem = updateItemMap[virtualImage.id]
				return new SyncTask.UpdateItem<VirtualImage, Map>(existingItem: virtualImage, masterItem: matchItem.masterItem)
			}
		}.onAdd { itemsToAdd ->
			addMissingVirtualImages(itemsToAdd)
		}.onUpdate { List<SyncTask.UpdateItem<VirtualImage, Map>> updateItems ->
			// Found the VirtualImage for this location.. just need to create the location
			addMissingVirtualImageLocationsForImages(updateItems)
		}.start()
	}

	private addMissingVirtualImages(List addList) {
		log.debug "addMissingVirtualImages ${addList?.size()}"
		Account account = cloud.account
		def regionCode = cloud.regionCode
		def adds = []
		def addExternalIds = []
		addList?.each {
			def imageConfig = buildVirtualImageConfig(it)
			def add = new VirtualImage(imageConfig)
			def locationConfig = buildLocationConfig(add)
			VirtualImageLocation location = new VirtualImageLocation(locationConfig)
			add.imageLocations = [location]
			addExternalIds << add.externalId
			adds << add
		}

		// Create em all!
		log.debug "About to create ${adds.size()} virtualImages"
		morpheusContext.virtualImage.create(adds, cloud).blockingGet()

	}

	private addMissingVirtualImageLocationsForImages(List<SyncTask.UpdateItem<VirtualImage, Map>> addItems) {
		log.debug "addMissingVirtualImageLocationsForImages ${addItems?.size()}"

		def locationAdds = []
		addItems?.each { add ->
			VirtualImage virtualImage = add.existingItem
			def locationConfig = buildLocationConfig(virtualImage)
			VirtualImageLocation location = new VirtualImageLocation(locationConfig)
			locationAdds << location
		}

		if(locationAdds) {
			log.debug "About to create ${locationAdds.size()} locations"
			morpheusContext.virtualImage.location.create(locationAdds, cloud).blockingGet()
		}
	}

	private updateMatchedVirtualImageLocations(List<SyncTask.UpdateItem<VirtualImageLocation, Map>> updateList) {
		log.debug "updateMatchedVirtualImageLocations: ${updateList?.size()}"

		List<VirtualImageLocation> existingLocations = updateList?.collect { it.existingItem }

		def imageIds = updateList?.findAll{ it.existingItem.virtualImage?.id }?.collect{ it.existingItem.virtualImage.id }
		def externalIds = updateList?.findAll{ it.existingItem.externalId }?.collect{ it.existingItem.externalId }
		List<VirtualImage> existingItems = []
		if(imageIds && externalIds) {
			def tmpImgProjs = morpheusContext.virtualImage.listSyncProjections(cloud.id).filter { img ->
				img.id in imageIds || (!img.systemImage && img.externalId != null && img.externalId in externalIds)
			}.toList().blockingGet()
			if(tmpImgProjs) {
				existingItems = morpheusContext.virtualImage.listById(tmpImgProjs.collect { it.id }).filter { img ->
					img.id in imageIds || img.imageLocations.size() == 0
				}.toList().blockingGet()
			}
		} else if(imageIds) {
			existingItems = morpheusContext.virtualImage.listById(imageIds).toList().blockingGet()
		}

		List<VirtualImageLocation> locationsToCreate = []
		List<VirtualImageLocation> locationsToUpdate = []
		List<VirtualImage> imagesToUpdate = []

		//updates
		updateList?.each { update ->
			def cloudItem = update.masterItem
			def virtualImageConfig = buildVirtualImageConfig(cloudItem)
			VirtualImageLocation imageLocation = existingLocations?.find { it.id == update.existingItem.id }
			if(imageLocation) {
				def save = false
				def saveImage = false
				def image = existingItems.find {it.id == imageLocation.virtualImage.id}
				if(image) {
					if (imageLocation.imageName != virtualImageConfig.name) {
						imageLocation.imageName = virtualImageConfig.name
						if (image && (image.refId == imageLocation.refId.toString())) {
							image.name = virtualImageConfig.name
							imagesToUpdate << image
							saveImage = true
						}
						save = true
					}
					if (imageLocation.imageRegion != virtualImageConfig.imageRegion) {
						imageLocation.imageRegion = virtualImageConfig.imageRegion
						save = true
					}
					if (image.remotePath != virtualImageConfig.remotePath) {
						image.remotePath = virtualImageConfig.remotePath
						saveImage = true
					}
					if (image.imageRegion != virtualImageConfig.imageRegion) {
						image.imageRegion = virtualImageConfig.imageRegion
						saveImage = true
					}
					if (image.minDisk != virtualImageConfig.minDisk) {
						image.minDisk = virtualImageConfig.minDisk
						saveImage = true
					}
					if (image.bucketId != virtualImageConfig.bucketId) {
						image.bucketId = virtualImageConfig.bucketId
						saveImage = true
					}
					if (save) {
						locationsToUpdate << imageLocation
					}
					if (saveImage) {
						imagesToUpdate << image
					}
				}
			} else {
				VirtualImage image = existingItems?.find { it.externalId == virtualImageConfig.externalId || it.name == virtualImageConfig.name }
				if(image) {
					//if we matched by virtual image and not a location record we need to create that location record
					def addLocation = new VirtualImageLocation(buildLocationConfig(image))
					locationsToCreate << addLocation
					image.deleted = false
					image.setPublic(false)
					imagesToUpdate << image
				}
			}

		}
		if(locationsToCreate.size() > 0 ) {
			morpheusContext.virtualImage.location.create(locationsToCreate, cloud).blockingGet()
		}
		if(locationsToUpdate.size() > 0 ) {
			morpheusContext.virtualImage.location.save(locationsToUpdate, cloud).blockingGet()
		}
		if(imagesToUpdate.size() > 0 ) {
			morpheusContext.virtualImage.save(imagesToUpdate, cloud).blockingGet()
		}
	}

	private removeMissingVirtualImageLocations(List removeList) {
		log.debug "removeMissingVirtualImageLocations: ${removeList?.size()}"
		morpheusContext.virtualImage.location.remove(removeList).blockingGet()
	}

	private buildVirtualImageConfig(Map cloudItem) {
		Account account = cloud.account
		//def regionCode = cloud.regionCode

		def imageConfig = [
				account    : account,
				category   : "kubevirt.image.${cloud.id}",
				name       : cloudItem.name,
				code       : "kubevirt.image.${cloud.id}.${cloudItem.uid}",
				imageType  : ImageType.qcow2,
				status     : 'Active',
				//minDisk    : cloudItem.status.resources.size_bytes?.toLong(),
				isPublic   : false,
				//remotePath : cloudItem.status.resources?.retrieval_uri_list?.getAt(0) ?: cloudItem.status.resources?.source_uri,
				externalId : cloudItem.uid,
				//imageRegion: regionCode,
				internalId : cloudItem.uid,
				uniqueId   : cloudItem.uid,
				//bucketId   : cloudItem.status.resources?.current_cluster_reference_list?.getAt(0)?.uuid
		]

		return imageConfig
	}

	private Map buildLocationConfig(VirtualImage image) {
		return [
				virtualImage: image,
				code        : "kubevirt.image.${cloud.id}.${image.externalId}",
				internalId  : image.internalId,
				externalId  : image.externalId,
				imageName   : image.name,
				//imageRegion : cloud.regionCode,
				isPublic    : false
		]
	}

	private Map buildLocationConfig(VirtualImageIdentityProjection imageLocationProj) {
		return [
				virtualImage: new VirtualImage(id: imageLocationProj.id),
				code        : "kubevirt.image.${cloud.id}.${imageLocationProj.externalId}",
				internalId  : imageLocationProj.externalId, // internalId and externalId match
				externalId  : imageLocationProj.externalId,
				imageName   : imageLocationProj.name,
				//imageRegion : cloud.regionCode
		]
	}

}