/*******************************************************************************
 * Permission is hereby granted, free of charge, to any person obtaining a copy of this software 
 * and associated documentation files (the "Software"), to deal in the Software without 
 * restriction, including without limitation the rights to use, copy, modify, merge, publish, 
 * distribute, sublicense, and/or sell copies of the Software, and to permit persons to whom the 
 * Software is furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all copies or 
 * substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR 
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY, FITNESS 
 * FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE AUTHORS OR 
 * COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER LIABILITY, WHETHER IN 
 * AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM, OUT OF OR IN CONNECTION 
 * WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE SOFTWARE.
 *
 * This software uses third-party components, distributed accordingly to their own licenses.
 *******************************************************************************/
package gov.rfb.adcopy;

import java.util.Arrays;
import java.util.stream.Collectors;

import org.apache.commons.cli.Option;
import org.apache.commons.cli.Options;

/**
 * All of the command line options that are defined to be used with AzureADCopy application.
 * 
 * @author Gustavo Figueiredo
 */
public class Settings {

	static final Option SRC_TENANT_ID = new Option("st", "src_tenant_id", true, "Tenant-id of source AAD");

	static final Option SRC_CLIENT_ID = new Option("sc", "src_client_id", true, "Client-id of application with read access to source AAD");

	static final Option SRC_CLIENT_SECRET = new Option("ss", "src_client_secret", true, "Client-secret of application with read access to source AAD");

	static final Option SRC_CLIENT_CERTIFICATE = new Option("scer", "src_client_certificate", true, "Certificate of the application with read access to source AAD (overrides the 'src_client_secret' parameter)");

	static final Option SRC_CLIENT_CERTIFICATE_PASSWORD = new Option("spwd", "src_client_certificate_password", true, "Password to open the certificate of the application with read access to source AAD (if the certificate informed in 'src_client_certificate' is in PFX/PKCS12 format)");

	static final Option DST_TENANT_ID = new Option("dt", "dst_tenant_id", true, "Tenant-id of destination AAD");

	static final Option DST_CLIENT_ID = new Option("dc", "dst_client_id", true, "Client-id of application with write access to destination AAD");

	static final Option DST_CLIENT_SECRET = new Option("ds", "dst_client_secret", true, "Client-secret of application with write access to destination AAD");

	static final Option DST_CLIENT_CERTIFICATE = new Option("dcer", "dst_client_certificate", true, "Certificate of the application with write access to destination AAD (overrides the 'dst_client_secret' parameter)");

	static final Option DST_CLIENT_CERTIFICATE_PASSWORD = new Option("dpwd", "dst_client_certificate_password", true, "Password to open the certificate of the application with write access to destination AAD (if the certificate informed in 'dst_client_certificate' is in PFX/PKCS12 format)");

	static final Option CREATE_MISSING_GROUPS = new Option("cmg", "create_missing_groups", false, "Optional parameter. If informed, will automatically create at destination any missing groups. If not informed, will only report their absence.");

	static final Option ALLOW_EMPTY_GROUPS = new Option("aeg", "allow_empty_groups", false,
			"Optional parameter (requires 'create_missing_groups'). Allows creating empty missing groups. "
					+ "By default, a missing group will not be created when empty in source tenant.");

	static final Option REMOVE_MEMBERS = new Option("rm", "remove_members", false, "Optional parameter. If informed, will remove members at destination that are no longer members in the same group at source. If not informed, will not remove them.");

	static final Option GROUP_PREFIX = new Option("g", "group_prefix", true, "Prefix used for selecting groups of interest in source AAD. Multiple optional prefixes may be informed separated by commas.");

	static final Option USER_FIELD_NAME = new Option("u", "user_field_name", true, "Optional parameter. If informed, expect to find this field informed for 'User' objects and will use this information for matching users from both AAD (e.g.: 'userPrincipalName', 'onPremisesSamAccountName', etc.). If not informed, use the 'displayName' field for matching users.");

	static final Option SRC_USER_FIELD_NAME = new Option("su", "src_user_field_name", true,
			"Optional parameter. 'User' object attribute name for matching users in source AAD. "
					+ "Can be used in conjunction with parameter 'dst_user_field_name' to accommodate a scenario of "
					+ "cross-tenant synchronization where a source user attribute has been mapped to a different name. "
					+ "Takes precedence over parameter 'user_field_name'");

	static final Option DST_USER_FIELD_NAME = new Option("du", "dst_user_field_name", true,
			"Optional parameter. 'User' object attribute name for matching users in destination AAD. "
					+ "Can be used in conjunction with parameter 'src_user_field_name' to accommodate a scenario of "
					+ "cross-tenant synchronization where a source user attribute has been mapped to a different name. "
					+ "Takes precedence over parameter 'user_field_name'");

	static final Option PREVIEW = new Option("p", "preview", false, "Optional parameter. If informed, will execute in 'preview mode' (i.e. it it won't change anything at the destination AAD, but will print at LOG whatever it would do)");
	
	static final Option THREADS = new Option("t", "threads", true, "Optional parameter. If informed, will execute this amount of threads for faster performance. If absent, will execute one single thread");

	static final Option HELP = new Option("h", "help", false, "Display this message and quit");

	/**
	 * Returns all of the defined command line options. Use reflection for enumerating all of the static fields of type 'Option' declared in this class.
	 */
	public static Options getOptions()
	{
		Options options = new Options();
		Arrays.asList(Settings.class.getDeclaredFields()).stream()
			.filter(field->field.getType().equals(Option.class)).collect(Collectors.toList())
			.stream().forEach(field->{
				try {
					options.addOption((Option) field.get(null));
				} catch (IllegalArgumentException|IllegalAccessException e) {
					// ignored
				}
			});
		
		return options;
	}
}
