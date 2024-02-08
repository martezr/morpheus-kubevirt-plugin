# Morpheus Kubevirt Plugin

[![GitHub tag (latest SemVer)](https://img.shields.io/github/v/tag/martezr/morpheus-kubevirt-plugin?label=release)](https://github.com/martezr/morpheus-kubevirt-plugin/releases) [![license](https://img.shields.io/github/license/martezr/morpheus-kubevirt-plugin.svg)]()

<img src="./src/assets/images/kubevirt-horizontal-color.svg" width="300px">

This is the **community** developed Morpheus plugin for integrating with the [Kubevirt virtualization solution](https://kubevirt.io/).

This plugin provides virtual machine provisioning, snapshot create, and snapshot restore.

|Feature|Description|
|-------|-----------|
| Host Discovery | The integration discovers and inventories kubevirt cluster hosts |
| Host Performance Metrics | |
| Virtual Machine Discovery | The integration discovers and inventories existing virtual machines|
| Service Plan Seeding|The integration bundles several service plans|
| Virtual Machine Tagging | The integration supports adding tags |

## Requirements

* **Kubernetes Service Account:** A service account is required for the integration to authenticate to the Kubernetes cluster.

## Configuration Settings

|Name|Description|Example|
|--|--|--|
|API URL|The Kubernetes API endpoint including the scheme and port|https://10.0.0.10:6443|
|Token||

# Getting Started
Once the plugin is loaded in the environment. Kubevirt becomes available as an option when creating a new cloud in Infrastructure -> Clouds.

The following information is required when adding a Kubevirt cloud:

## Creating a Morpheus Service Account

The integration requires a token for authenticating with the Kubernetes cluster.

1. Create a dedicated Kubernetes service account for the Morpheus integration.

```
kubectl create sa morpheus
```

2. Create a dedicated role binding to grant the service account permissions.

```
kubectl create clusterrolebinding morpheus-cluster-admin --clusterrole=cluster-admin --serviceaccount=default:morpheus
```

3. Generate a token for the service account.

```
kubectl create token morpheus --duration 8760h
```