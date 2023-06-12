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

	static final Option DST_TENANT_ID = new Option("dt", "dst_tenant_id", true, "Tenant-id of destination AAD");

	static final Option DST_CLIENT_ID = new Option("dc", "dst_client_id", true, "Client-id of application with write access to destination AAD");

	static final Option DST_CLIENT_SECRET = new Option("ds", "dst_client_secret", true, "Client-secret of application with write access to destination AAD");
	
	static final Option CREATE_MISSING_GROUPS = new Option("cmg", "create_missing_groups", false, "Optional parameter. If informed, will automatically create at destination any missing groups. If not informed, will only report their absence.");

	static final Option REMOVE_MEMBERS = new Option("rm", "remove_members", false, "Optional parameter. If informed, will remove members at destination that are no longer members in the same group at source. If not informed, will not remove them.");

	static final Option GROUP_PREFIX = new Option("g", "group_prefix", true, "Prefix used for selecting groups of interest in source AAD. Multiple optional prefixes may be informed separated by commas.");

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
