# README - Setup LVS virtual server

This recipe sets up an LVS-NAT virtual server (actually, a pair of routers in a highly-available mode) using `keepalived`.

In this setup, we have an external and an internal network. The external is the one facing the end-user of a service, while the internal interacts with the backend services (the *real* servers in LVS terminology). Each of these networks have a virtual IP (VIP): for the external network the VIP is the endpoint that users know to access services, while on the internal network the VIP is the gateway used by backend servers (note that in order for LVS-NAT to work, the return path for a packet must be the same, so we need to configure those gateways!).

On failover, both VIPs switch from master machine to the backup machine.

On the internal network, both routers (`master` and `backup`) have real IPs. The real IPs are not touched by `keepalived`, but are used for the heartbeat communication (the VRRP *advertisment*) between the two routers.

On the external network, we have no real IP (apart from the link-local IPv6 address). So, on the external network, no advertisment packets are exchanged. The external VIP simply follows the transition of the internal VIP (see `track_interface`, and `virtual_ipaddress` in the VRRP instance configuration).

For instance, in the example inventory (`hosts.yml.example`), we have the following IPs:

  - 192.168.1.200 (VIP on external)
  - 192.168.5.4 (master real IP on internal)
  - 192.168.5.5 (backup real IP on internal)
  - 192.168.5.3 (VIP on internal, the gateway for real servers)

## 1. Prerequisites

If Python package `netaddr` is not installed (locally), install it (`pip install netaddr`).

Copy `hosts.yml.example` to `hosts.yml` and configure this inventory file.

Create secret files under `secret/smtp/user/USERNAME` and `secrets/smtp/ca.pem`

## 2. Setup

The recipe is basicly a numbered collection of Ansible playbooks. 

If the machine infrastructure is allready setup, you can directly run the Ansible playbooks. For example:

    ansible-playbook -i hosts.yml --become -v play-1-setup-mailer.yml
    ansible-playbook -i hosts.yml --become -v play-2-virtual-server.yml

