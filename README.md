# ADCopy

ADCopy is a small application that may be used for copying membership information of groups defined in an Azure Active Directory (the 'source') to another Azure Active Directory (the 'destination').

This application connects to one AAD (the 'source'), queries for all groups starting with one of the provided prefixes, and compare each group's members with those ones informed in another AAD. It will then include or exclude members in the corresponding groups at the destination AAD.

The objects from each AAD (groups and users) are compared by their names, usually taken from the 'displayName' field, but may be another field.

The application requires the following API permissions (Application type) at the source AAD (admin consent required): 
- Group.Read.All
- GroupMember.Read.All
- User.Read.All

The application requires the following API permissions (Application type) at the destination AAD (admin consent required): 
- Group.ReadWrite.All
- GroupMember.ReadWrite.All
- User.Read.All

Several parameters must be defined, either through command line options or through environment variables (with the same names as the corresponding command line options, prefixed with 'aadcopy_').

Execute with the '--help' command line option for listing all of the possible parameters.


## Build

In order to build this application, simply run the very basic:
```
mvn package
```


## Examples

For replicating the membership information for all groups defined in source AAD for which the group name starts with the prefix 'SYSTEM.', skipping the missing groups at destination, with 10 concurrent threads, you may run something like this:
```
mvn exec:java -Dexec.mainClass=gov.rfb.adcopy.AzureADCopy -Dexec.args="--src_tenant_id {...} --src_client_id {...} --src_client_secret {...} --dst_tenant_id {...} --dst_client_id {...} --dst_client_secret {...} --group_prefix SYSTEM. --threads 10"
```

The same as above, but using a certificate file (either in PEM or PFX format) for authentication (the files must also include the private key).
```
mvn exec:java -Dexec.mainClass=gov.rfb.adcopy.AzureADCopy -Dexec.args="--src_tenant_id {...} --src_client_id {...} --src_client_certificate {...} --dst_tenant_id {...} --dst_client_id {...} --dst_client_certificate {...} --group_prefix SYSTEM. --threads 10"
```

For replicating the membership information for all groups defined in source AAD for which the group name starts with either the prefix 'SYSTEM.' or the prefix 'CLOUD.', skipping the missing groups at destination, with 10 concurrent threads, you may run something like this:
```
mvn exec:java -Dexec.mainClass=gov.rfb.adcopy.AzureADCopy -Dexec.args="--src_tenant_id {...} --src_client_id {...} --src_client_secret {...} --dst_tenant_id {...} --dst_client_id {...} --dst_client_secret {...} --group_prefix SYSTEM.,CLOUD. --threads 10"
```

For replicating the membership information for all groups defined in source AAD for which the group name starts with the prefix 'SYSTEM.', creating the missing groups at destination, with 10 concurrent threads, you may run something like this:
```
mvn exec:java -Dexec.mainClass=gov.rfb.adcopy.AzureADCopy -Dexec.args="--src_tenant_id {...} --src_client_id {...} --src_client_secret {...} --dst_tenant_id {...} --dst_client_id {...} --dst_client_secret {...} --group_prefix SYSTEM. --threads 10 --create_missing_groups"
```

For replicating the membership information for all groups defined in source AAD for which the group name starts with the prefix 'SYSTEM.', skipping the missing groups at destination, with 10 concurrent threads, considering the 'onPremisesSamAccountName' field for comparing users, you may run something like this:
```
mvn exec:java -Dexec.mainClass=gov.rfb.adcopy.AzureADCopy -Dexec.args="--src_tenant_id {...} --src_client_id {...} --src_client_secret {...} --dst_tenant_id {...} --dst_client_id {...} --dst_client_secret {...} --group_prefix SYSTEM. --threads 10" --user_field_name onPremisesSamAccountName
```
