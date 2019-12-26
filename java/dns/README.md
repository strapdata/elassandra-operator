# Dns module

The DnsUpdater is used in the sidecar to record the SEED_HOST_ID in the external DNS zone as a contact point.
When removing the datacenter, the DNS record is removed by the Elassandra Operator through the DnsPlugin.

This module is intended to implements various DNS provider, starting with the Azure one.