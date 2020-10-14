# README - Setup a Kubernetes cluster

This is a collection of Ansible scripts that automate the installation of a Kubernetes cluster. 

This recipe is tested *only* on Ubuntu 16.04 hosts.

## 1. Prerequisites

### 1.1. SSH Keys

Place public key `id_rsa.pub` into `keys` directory. This key will authenticate user `user` as a sudoer user in the entire cluster.

### 1.2. Inventory file

Copy `hosts.yml.example` into `hosts.yml` and configure your hosts.

## 2. Play

We build the cluster as a sequence of *provision* stages. Each stage is represented by an Ansible playbook.These playbooks can be run either directly (via `ansible-playbook`), or indirectly as Vagrant provisioning steps (via `vagrant provision --provision-with=NAME`).

The stages are (in this order):

  1. `play-basic`: Common setup for all machines (basic tools, CA certificates, hostnames etc.)
  2. `play-docker`: Setup Docker engine on all cluster nodes (the Container Runtime for the cluster).
  3. `play-kubernetes-init-cluster`: Initialize the Kubernetes cluster on the control-plane machine. This step includes setup for networking (Container Networking).
  4. `play-kubernetes-join-cluster`: Join worker nodes on the Kubernetes cluster
  5. `play-docker-registry-as-deployment`: (optional) Setup a internal Docker registry as a Kubernetes deployment

