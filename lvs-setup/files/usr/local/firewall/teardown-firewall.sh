#!/bin/bash


iptables -t filter -D INPUT -m udp -p udp -i ${PUBLIC_IP4_IFACE} --dport 123 -j REJECT

iptables -t nat -D POSTROUTING -m comment --out-interface "${EXTERNAL_IFACE}" --source "${INTERNAL_NETWORK}" -j MASQUERADE \
   --comment "Masquerade packets from internal network (to ${EXTERNAL_IFACE})"

iptables -t nat -F PREROUTING

