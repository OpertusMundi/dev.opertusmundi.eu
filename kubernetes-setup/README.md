# README - Setup a Kubernetes cluster

This is a collection of Ansible scripts that automate the installation of a Kubernetes cluster. 

This recipe is tested *only* on Ubuntu 16.04 hosts.

## 1. Prerequisites

### 1.1. SSH Keys

Place public key `id_rsa.pub` into `keys` directory. This key will authenticate user `user` as a sudoer user in the entire cluster.

### 1.2. Inventory file

Copy `hosts.yml.example` into `hosts.yml` and configure your hosts.

## 2. Play

__Todo__
