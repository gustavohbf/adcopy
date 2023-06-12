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

import static gov.rfb.adcopy.Settings.*;

import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.concurrent.Executors;
import java.util.concurrent.Phaser;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.LongAdder;
import java.util.function.Function;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.stream.Collectors;

import org.apache.commons.cli.CommandLine;
import org.apache.commons.cli.CommandLineParser;
import org.apache.commons.cli.HelpFormatter;
import org.apache.commons.cli.Option;
import org.apache.commons.cli.Options;
import org.apache.commons.cli.ParseException;
import org.apache.commons.cli.PosixParser;

import com.azure.core.credential.TokenCredential;
import com.azure.identity.ClientSecretCredentialBuilder;
import com.microsoft.graph.authentication.TokenCredentialAuthProvider;
import com.microsoft.graph.core.ClientException;
import com.microsoft.graph.logger.ILogger;
import com.microsoft.graph.logger.LoggerLevel;
import com.microsoft.graph.models.DirectoryObject;
import com.microsoft.graph.models.Group;
import com.microsoft.graph.models.User;
import com.microsoft.graph.options.HeaderOption;
import com.microsoft.graph.requests.DirectoryObjectCollectionWithReferencesPage;
import com.microsoft.graph.requests.DirectoryObjectCollectionWithReferencesRequestBuilder;
import com.microsoft.graph.requests.GraphServiceClient;
import com.microsoft.graph.requests.GroupCollectionPage;
import com.microsoft.graph.requests.GroupCollectionRequestBuilder;
import com.microsoft.graph.requests.UserCollectionPage;
import com.microsoft.graph.requests.UserCollectionRequestBuilder;

import okhttp3.Request;

/**
 * This is a small application that may be used for copying the groups' memberships from one Azure Active Directory to another.<BR>
 * <BR>
 * This application connects to one AAD (the 'source'), queries for all groups starting with one of the provided prefixes, and compare each group's members
 * with those ones informed in another AAD. It will then include or exclude members in the corresponding groups at the destination AAD.<BR>
 * The objects from each AAD (groups and users) are compared by their names (displayName), not by their id's, which may be different.<BR>
 * <BR>
 * The application requires the following API permissions (Application type) at the source AAD (admin consent required):<BR>
 * - Group.Read.All<BR>
 * - GroupMember.Read.All<BR>
 * - User.Read.All<BR>
 * <BR>
 * The application requires the following API permissions (Application type) at the destination AAD (admin consent required):<BR>
 * - Group.ReadWrite.All<BR>
 * - GroupMember.ReadWrite.All<BR>
 * - User.Read.All<BR>
 * <BR>
 * Several parameters must be defined, either through command line options or through environment variables (with the same names as the corresponding command line options, prefixed with 'aadcopy_').<BR>
 * <BR>
 * Execute with the '--help' command line option for listing all of the possible parameters.<BR>
 * 
 * @author Gustavo Figueiredo
 */
public class AzureADCopy implements Runnable
{
	private static final Logger log = Logger.getLogger(AzureADCopy.class.getName());
	
	/**
	 * Default request options used in all requests related to MSGraph API
	 */
	private static final List<com.microsoft.graph.options.Option> DEFAULT_REQUEST_OPTIONS = Collections.singletonList(new HeaderOption("ConsistencyLevel", "eventual"));
	
	/**
	 * Expected prefix for environment variables that should be used for providing the corresponding command line options, in replacement to them.
	 */
	private static final String PREFIX_ENV = "aadcopy_";
	
	private static final int DEFAULT_NUMBER_THREADS = 1;
	
	private static final int SC_NOT_FOUND = 404;

	/**
	 * The tenant-id of the source AAD
	 */
	private String sourceTenantId;
	
	/**
	 * The client-id of the registered application at the source AAD
	 */
	private String sourceClientId;
	
	/**
	 * The client-secret of the registered application at the source AAD
	 */
	private char[] sourceSecret;
	
	/**
	 * The tenant-id of the destination AAD
	 */
	private String destinationTenantId;
	
	/**
	 * The client-id of the registered application at the destination AAD
	 */
	private String destinationClientId;
	
	/**
	 * The client-secret of the registered application at the destination AAD
	 */
	private char[] destinationSecret;
	
	/**
	 * The prefix to be used for searching group names. Multiple prefixes may be informed separated by commas.
	 */
	private String groupPrefix;
	
	/**
	 * Several counters incremented during the procedure
	 */
	private final LongAdder countGroups, countUsersMembers, countMissingGroupsAtDestination, countGroupsCreated, countErrorsCreatingGroups, countMembersCreated, countErrorsCreatingMembers, countMembersRemoved, countErrorsRemovingMembers;
	
	/**
	 * Collects distinct user names that are known to be missing at the destination AAD
	 */
	private final Set<String> missingUsersAtDestination;
	
	/**
	 * Timestamp (in epoch ms) of the start of operation
	 */
	private long timestampStart;
	
	/**
	 * Timestamp (in epoch ms) of the end of operation
	 */
	private long timestampEnd;
	
	/**
	 * Indicates if it should create missing groups at the destination AAD
	 */
	private boolean createMissingGroups;
	
	/**
	 * Indicates if it should remove members at the destination AAD according to the source AAD
	 */
	private boolean removeMembers;
	
	/**
	 * Indicates it should create new members at the destination AAD according to the source AAD
	 */
	private boolean createMembers = true;
	
	/**
	 * Indicates if it should run in 'preview mode' (i.e. it won't change anything at the destination AAD, but will print at LOG whatever
	 * it would do).
	 */
	private boolean previewMode;
	
	/**
	 * Number of different threads to execute at the same time for faster performance
	 */
	private int threads = DEFAULT_NUMBER_THREADS;
	
	/**
	 * Entry point of the application
	 */
    public static void main( String[] args ) throws Exception
    {
    	CommandLine cmd = parseOptions(args);
    	if (cmd==null)
    		return;
    	
    	AzureADCopy procedure = new AzureADCopy();
    	
    	procedure.setGroupPrefix(getRequiredParameter(GROUP_PREFIX, cmd));
    	
    	procedure.setSourceTenantId(getRequiredParameter(SRC_TENANT_ID, cmd));
    	procedure.setSourceClientId(getRequiredParameter(SRC_CLIENT_ID, cmd));
    	procedure.setSourceSecret(getRequiredParameter(SRC_CLIENT_SECRET, cmd).toCharArray());

    	procedure.setDestinationTenantId(getRequiredParameter(DST_TENANT_ID, cmd));
    	procedure.setDestinationClientId(getRequiredParameter(DST_CLIENT_ID, cmd));
    	procedure.setDestinationSecret(getRequiredParameter(DST_CLIENT_SECRET, cmd).toCharArray());
    	
    	procedure.setCreateMissingGroups(cmd.hasOption(CREATE_MISSING_GROUPS.getOpt()));
    	procedure.setRemoveMembers(cmd.hasOption(REMOVE_MEMBERS.getOpt()));
    	procedure.setPreviewMode(cmd.hasOption(PREVIEW.getOpt()));
    	
    	if (cmd.hasOption(THREADS.getOpt())) {
    		procedure.setThreads(Integer.valueOf(getParameter(THREADS, cmd).get()));
    	}
    	
    	if (procedure.isPreviewMode())
    		log.log(Level.INFO, "Starting AzureADCopy in PREVIEW MODE...");
    	else
    		log.log(Level.INFO, "Starting AzureADCopy...");
    	
    	if (log.isLoggable(Level.FINE)) {
    		log.log(Level.FINE, "Source tenant: "+procedure.getSourceTenantId());
    		log.log(Level.FINE, "Destination tenant: "+procedure.getDestinationTenantId());
    		log.log(Level.FINE, "Group prefix: "+procedure.getGroupPrefix());
    	}
    	
    	procedure.run();
    	
    	log.log(Level.INFO, procedure.getSummary());
    }
    
    /**
     * Parse command line options. Returns NULL if if should not proceed (e.g. in case of displaying help instructions).
     */
	public static CommandLine parseOptions(String[] args)
	{
		Options options = Settings.getOptions();

		CommandLineParser parser = new PosixParser();
		CommandLine cmdLine = null;
		try
		{
			cmdLine = parser.parse(options, args);
		}
		catch (ParseException ex)
		{
			log.log(Level.SEVERE, "Error parsing command line options", ex);
			return null;
		}
		if (cmdLine==null) {
			throw new IllegalStateException("Command line options could not be parsed!");
		}
		if (!cmdLine.getArgList().isEmpty()) {
			throw new IllegalStateException("Unknown command line options: " + cmdLine.getArgList());
		}
		if (cmdLine.hasOption(HELP.getOpt())) {
			HelpFormatter formatter = new HelpFormatter();
			formatter.printHelp("help", options);
			return null;
		}
		return cmdLine;
	}
	
	/**
	 * Returns the optional parameter, checking the command line and also the environment variable (in this order)
	 */
	public static Optional<String> getParameter(Option option, CommandLine cmdLine) {
		String value = cmdLine.getOptionValue(option.getOpt());
		if (value!=null)
			return Optional.of(value);
		return Optional.ofNullable(System.getenv(PREFIX_ENV+option.getLongOpt()));
	}

	/**
	 * Returns the required parameter, throwing exception if it's not found, checking the command line and also the environment variable (in this order)
	 */
	public static String getRequiredParameter(Option option, CommandLine cmdLine) throws MissingRequiredParameterException {
		return getParameter(option, cmdLine).orElseThrow(()->new MissingRequiredParameterException(option.getLongOpt()));
	}
	
	/**
	 * Given the application credentials, returns the client to be used with the MSGraph API
	 */
	public static GraphServiceClient<Request> getGraphClient(String tenantId, String clientId, char[] clientSecret) {
		TokenCredential credential = new ClientSecretCredentialBuilder()
				.clientId(clientId)
				.tenantId(tenantId)
				.clientSecret(new String(clientSecret)).build();

		TokenCredentialAuthProvider tokenCredentialAuthProvider = new TokenCredentialAuthProvider(credential);

		return GraphServiceClient.builder().logger(new ILogger() {				
			@Override
			public void setLoggingLevel(LoggerLevel level) {
				switch (level) {
				case ERROR:
					log.setLevel(Level.SEVERE);
					break;
				case DEBUG:
					log.setLevel(Level.FINEST);
					break;
				default:
				}
			}				
			@Override
			public void logError(String message, Throwable throwable) {
				log.log(Level.SEVERE, message, throwable);
			}				
			@Override
			public void logDebug(String message) {
				log.log(Level.FINEST, message);
			}				
			@Override
			public LoggerLevel getLoggingLevel() {
				if (Level.FINEST.equals(log.getLevel()))
					return LoggerLevel.DEBUG;
				else
					return LoggerLevel.ERROR;
			}
		}).authenticationProvider(tokenCredentialAuthProvider).buildClient();

	}
	
	/**
	 * Returns a group defined in AAD whose displayName is equal to the first parameter.
	 */
	public static Optional<Group> getGroupWithName(String name, GraphServiceClient<Request> graphClient) {
		GroupCollectionPage page = graphClient.groups().buildRequest(DEFAULT_REQUEST_OPTIONS)
			.select("displayName,id")
			.filter(String.format("startswith(displayName, '%s')", name.replace("'", "''")))
			.get();
		while (page!=null) {
			List<Group> currentPage = page.getCurrentPage();
			Optional<Group> group = currentPage.stream().filter(g->name.equalsIgnoreCase(g.displayName)).findFirst();
			if (group.isPresent())
				return group;
			GroupCollectionRequestBuilder nextPage = page.getNextPage();
			if (nextPage==null)
				break;
			else {
				page = nextPage.buildRequest(DEFAULT_REQUEST_OPTIONS).get();
			}
		}
		return Optional.empty();
	}

	/**
	 * Returns an user defined in AAD whose displayName is equal to the first parameter.
	 */
	public static Optional<User> getUserWithName(String name, GraphServiceClient<Request> graphClient) {
		UserCollectionPage page = graphClient.users().buildRequest(DEFAULT_REQUEST_OPTIONS)
			.select("displayName,id,userPrincipalName")
			.filter(String.format("startswith(displayName, '%s')", name.replace("'", "''")))
			.get();
		while (page!=null) {
			List<User> currentPage = page.getCurrentPage();
			Optional<User> user = currentPage.stream().filter(g->name.equalsIgnoreCase(g.displayName)).findFirst();
			if (user.isPresent())
				return user;
			UserCollectionRequestBuilder nextPage = page.getNextPage();
			if (nextPage==null)
				break;
			else {
				page = nextPage.buildRequest(DEFAULT_REQUEST_OPTIONS).get();
			}
		}
		return Optional.empty();
	}

	/**
	 * Returns the full list of users that are members of the group whose id is informed.
	 */
	public static List<User> getUsersMembers(String groupId, GraphServiceClient<Request> graphClient) {
		if (groupId==null)
			return Collections.emptyList();
		List<User> users = new LinkedList<>();
		try {
			DirectoryObjectCollectionWithReferencesPage  pageOfMembers =
				graphClient.groups(groupId).members().buildRequest(DEFAULT_REQUEST_OPTIONS)
				.select("displayName,id,userPrincipalName")
				.get();
			while (pageOfMembers!=null) {
				List<DirectoryObject> members = pageOfMembers.getCurrentPage();
				for (DirectoryObject member: members) {
					if (!(member instanceof User))
						continue;
					User user = (User)member;
					users.add(user);
				}
				DirectoryObjectCollectionWithReferencesRequestBuilder nextPage = pageOfMembers.getNextPage();
				if (nextPage==null)
					break;
				else {
					pageOfMembers = nextPage.buildRequest().get();
				}
			}
		}
		catch (com.microsoft.graph.http.GraphServiceException ex) {
			if (ex.getResponseCode()!=SC_NOT_FOUND) {
				throw ex;
			}
		}
		return users;
	}
	
	public AzureADCopy () {
		countGroups = new LongAdder();
		countUsersMembers = new LongAdder();
		countMissingGroupsAtDestination = new LongAdder();
		countErrorsCreatingGroups = new LongAdder();
		countGroupsCreated = new LongAdder();
		countMembersCreated = new LongAdder();
		countErrorsCreatingMembers = new LongAdder();
		countMembersRemoved = new LongAdder();
		countErrorsRemovingMembers = new LongAdder();
		missingUsersAtDestination = Collections.synchronizedSet(new TreeSet<>(String.CASE_INSENSITIVE_ORDER));
	}

	/**
	 * The tenant-id of the source AAD
	 */
	public String getSourceTenantId() {
		return sourceTenantId;
	}

	/**
	 * The tenant-id of the source AAD
	 */
	public void setSourceTenantId(String sourceTenantId) {
		this.sourceTenantId = sourceTenantId;
	}

	/**
	 * The client-id of the registered application at the source AAD
	 */
	public String getSourceClientId() {
		return sourceClientId;
	}

	/**
	 * The client-id of the registered application at the source AAD
	 */
	public void setSourceClientId(String sourceClientId) {
		this.sourceClientId = sourceClientId;
	}

	/**
	 * The client-secret of the registered application at the source AAD
	 */
	public char[] getSourceSecret() {
		return sourceSecret;
	}

	/**
	 * The client-secret of the registered application at the source AAD
	 */
	public void setSourceSecret(char[] sourceSecret) {
		this.sourceSecret = sourceSecret;
	}

	/**
	 * The tenant-id of the destination AAD
	 */
	public String getDestinationTenantId() {
		return destinationTenantId;
	}

	/**
	 * The tenant-id of the destination AAD
	 */
	public void setDestinationTenantId(String destinationTenantId) {
		this.destinationTenantId = destinationTenantId;
	}

	/**
	 * The client-id of the registered application at the destination AAD
	 */
	public String getDestinationClientId() {
		return destinationClientId;
	}

	/**
	 * The client-id of the registered application at the destination AAD
	 */
	public void setDestinationClientId(String destinationClientId) {
		this.destinationClientId = destinationClientId;
	}

	/**
	 * The client-secret of the registered application at the destination AAD
	 */
	public char[] getDestinationSecret() {
		return destinationSecret;
	}

	/**
	 * The client-secret of the registered application at the destination AAD
	 */
	public void setDestinationSecret(char[] destinationSecret) {
		this.destinationSecret = destinationSecret;
	}

	/**
	 * The prefix to be used for searching group names. Multiple prefixes may be informed separated by commas.
	 */
	public String getGroupPrefix() {
		return groupPrefix;
	}

	/**
	 * The prefix to be used for searching group names. Multiple prefixes may be informed separated by commas.
	 */
	public void setGroupPrefix(String groupPrefix) {
		this.groupPrefix = groupPrefix;
	}
	
	/**
	 * Indicates if it should create missing groups at the destination AAD
	 */
	public boolean isCreateMissingGroups() {
		return createMissingGroups;
	}

	/**
	 * Indicates if it should create missing groups at the destination AAD
	 */
	public void setCreateMissingGroups(boolean createMissingGroups) {
		this.createMissingGroups = createMissingGroups;
	}

	/**
	 * Indicates if it should remove members at the destination AAD according to the source AAD
	 */
	public boolean isRemoveMembers() {
		return removeMembers;
	}

	/**
	 * Indicates if it should remove members at the destination AAD according to the source AAD
	 */
	public void setRemoveMembers(boolean removeMembers) {
		this.removeMembers = removeMembers;
	}

	/**
	 * Indicates it should create new members at the destination AAD according to the source AAD
	 */
	public boolean isCreateMembers() {
		return createMembers;
	}

	/**
	 * Indicates it should create new members at the destination AAD according to the source AAD
	 */
	public void setCreateMembers(boolean createMembers) {
		this.createMembers = createMembers;
	}

	/**
	 * Indicates if it should run in 'preview mode' (i.e. it won't change anything at the destination AAD, but will print at LOG whatever
	 * it would do).
	 */
	public boolean isPreviewMode() {
		return previewMode;
	}

	/**
	 * Indicates if it should run in 'preview mode' (i.e. it won't change anything at the destination AAD, but will print at LOG whatever
	 * it would do).
	 */
	public void setPreviewMode(boolean previewMode) {
		this.previewMode = previewMode;
	}

	/**
	 * Number of different threads to execute at the same time for faster performance
	 */
	public int getThreads() {
		return threads;
	}

	/**
	 * Number of different threads to execute at the same time for faster performance
	 */
	public void setThreads(int threads) {
		this.threads = threads;
	}

	/**
	 * Clears all the internal counters at the start of the procedure
	 */
	public void clearCounters() {
		countGroups.reset();
		countUsersMembers.reset();
		countMissingGroupsAtDestination.reset();
		countErrorsCreatingGroups.reset();
		countGroupsCreated.reset();
		countMembersCreated.reset();
		countErrorsCreatingMembers.reset();
		countMembersRemoved.reset();
		countErrorsRemovingMembers.reset();
		missingUsersAtDestination.clear();
	}
	
	/**
	 * Returns the object to be used as a client of the source AAD, given the provided credentials
	 */
	public GraphServiceClient<Request> getSourceGraphClient() {
		return getGraphClient(sourceTenantId, sourceClientId, sourceSecret);
	}
	
	/**
	 * Returns the object to be used as a client of the destination AAD, given the provided credentials
	 */
	public GraphServiceClient<Request> getDestinationGraphClient() {
		return getGraphClient(destinationTenantId, destinationClientId, destinationSecret);
	}
	
	/**
	 * Executes the whole procedure given the credentials and other configurations
	 */
	@Override
	public void run() {
		
		clearCounters();
		timestampStart = System.currentTimeMillis();
		
		try {
		
			GraphServiceClient<Request> sourceGraphClient = getSourceGraphClient();
			GraphServiceClient<Request> destinationGraphClient = getDestinationGraphClient();
			
			// Search source AAD for any groups starting with the provided prefix (may be more than one prefix)
			
			final String groupNameFilter =
				Arrays.stream(groupPrefix.split(","))
				.map(gp->String.format("startswith(displayName, '%s')",gp))
				.collect(Collectors.joining(" or "));
			
			GroupCollectionPage pagesOfGroupsAtSource =
			sourceGraphClient.groups().buildRequest(DEFAULT_REQUEST_OPTIONS)
				.select("displayName,id")
				.filter(groupNameFilter)
				.get();
						
			final ThreadPoolExecutor executor = (threads>1) ? (ThreadPoolExecutor)Executors.newFixedThreadPool(threads) : null;
			final Phaser phaser = new Phaser(1);
			while (pagesOfGroupsAtSource!=null) {
				List<Group> pageOfGroupsAtSource = pagesOfGroupsAtSource.getCurrentPage();
				for (Group group: pageOfGroupsAtSource) {
					if (executor==null) {
						copyMembersOfGroup(group, sourceGraphClient, destinationGraphClient, null);
					}
					else {
						phaser.register();
						executor.submit(()->{
							try {
								copyMembersOfGroup(group, sourceGraphClient, destinationGraphClient, executor);
							}
							finally {
								phaser.arriveAndDeregister();
							}
						});
					}
				}
				GroupCollectionRequestBuilder nextPage = pagesOfGroupsAtSource.getNextPage();
				if (nextPage==null)
					break;
				else {
					pagesOfGroupsAtSource = nextPage.buildRequest(DEFAULT_REQUEST_OPTIONS).get();
				}
			}
			
			if (executor!=null) {
				phaser.arriveAndAwaitAdvance();
				executor.shutdown();
				try {
					executor.awaitTermination(1, TimeUnit.DAYS);
				} catch (InterruptedException ex) {
					Thread.currentThread().interrupt();
				}
			}

		}
		finally {
			timestampEnd = System.currentTimeMillis();
		}
	}
	
	/**
	 * Method called for each group found in the source AAD matching the required prefix.
	 * @param group Group found (only 'id' and 'displayName' fields are needed)
	 * @param sourceGraphClient Object to be used as a client of the source AAD, given the provided credentials
	 * @param destinationGraphClient Object to be used as a client of the destination AAD, given the provided credentials
	 * @param executor Object used for concurrent work. It's NULL if it should execute in this same thread.
	 */
	public void copyMembersOfGroup(Group group, GraphServiceClient<Request> sourceGraphClient, GraphServiceClient<Request> destinationGraphClient,ThreadPoolExecutor executor) {
		if (log.isLoggable(Level.FINE))
			log.log(Level.FINE, String.format("Copying members of group %s", group.displayName));
		countGroups.increment();
		
		// Get the current users members at source
		List<User> usersMembersAtSource = getUsersMembers(group.id, sourceGraphClient);
		countUsersMembers.add(usersMembersAtSource.size());
		
		// Seek at destination a group with the same group name
		Group groupAtDestination = getGroupWithName(group.displayName, destinationGraphClient)
			.orElseGet(()->{
				if (usersMembersAtSource.isEmpty())
					return null; // If the group is missing at destination, but there are not members at source, just ignore it
				countMissingGroupsAtDestination.increment();
				if (createMissingGroups) {
					try {
						return createMissingGroup(group.id, sourceGraphClient, destinationGraphClient);
					}
					catch (ClientException ex) {
						countErrorsCreatingGroups.increment();
						if (log.isLoggable(Level.WARNING))
							log.log(Level.WARNING, String.format("Failed to create group at destination: %s", group.displayName), ex);
						return null;
					}
				}
				else {
					if (log.isLoggable(Level.WARNING))
						log.log(Level.WARNING, String.format("Missing group at destination: %s", group.displayName));
					return null;
				}
				
			});
		if (groupAtDestination==null) {
			return;
		}
		
		// Get the current users members at destination
		List<User> usersMembersAtDestination = getUsersMembers(groupAtDestination.id, destinationGraphClient);
		
		// Check which users are to be created and which users are to be removed from the groups's membership
		final Map<String, User> usersMembersAtSourceByName = Collections.unmodifiableMap(usersMembersAtSource.stream().collect(Collectors.toMap(
				/*keyMapper*/u->u.displayName, 
				/*valueMapper*/Function.identity(), 
				/*mergeFunction*/(a,b)->a, 
				/*mapSupplier*/()->new TreeMap<>(String.CASE_INSENSITIVE_ORDER))));
		final Map<String, User> usersMembersAtDestinationByName = Collections.unmodifiableMap(usersMembersAtDestination.stream().collect(Collectors.toMap(
			/*keyMapper*/u->u.displayName, 
			/*valueMapper*/Function.identity(), 
			/*mergeFunction*/(a,b)->a, 
			/*mapSupplier*/()->new TreeMap<>(String.CASE_INSENSITIVE_ORDER))));

		if (createMembers) {
			// Check which users are to be created
			for (Map.Entry<String, User> entry: usersMembersAtSourceByName.entrySet()) {
				String displayName = entry.getKey();
				if (usersMembersAtDestinationByName.containsKey(displayName))
					continue;
				if (missingUsersAtDestination.contains(displayName))
					continue;
				// New member to be included at destination
				Runnable addNewMember = ()->{
					Optional<User> userAtDestination = getUserWithName(displayName, destinationGraphClient);
					if (userAtDestination.isPresent()) {
						try {
							addMember(userAtDestination.get(), groupAtDestination, destinationGraphClient);
						}
						catch (ClientException ex) {
							countErrorsCreatingMembers.increment();
							if (log.isLoggable(Level.WARNING))
								log.log(Level.WARNING, String.format("Failed to include user %s as member of group %s at destination", userAtDestination.get().displayName, groupAtDestination.displayName), ex);
						}
					}
					else {
						missingUsersAtDestination.add(displayName);
						if (log.isLoggable(Level.WARNING))
							log.log(Level.WARNING, String.format("Missing user at destination: %s", displayName));
					}
				};
				if (executor==null)
					addNewMember.run();
				else
					executor.submit(addNewMember);
			}
		}

		if (removeMembers) {
			// Check which users are to be removed
			for (Map.Entry<String, User> entry: usersMembersAtDestinationByName.entrySet()) {
				String displayName = entry.getKey();
				if (usersMembersAtSourceByName.containsKey(displayName))
					continue;
				// Existing member to be excluded at destination
				Runnable removeMember = ()->{
					User userAtDestination = entry.getValue();
					try {
						removeMember(userAtDestination, groupAtDestination, destinationGraphClient);
					}
					catch (ClientException ex) {
						countErrorsRemovingMembers.increment();
						if (log.isLoggable(Level.WARNING))
							log.log(Level.WARNING, String.format("Failed to remove user %s as member of group %s at destination", userAtDestination.displayName, groupAtDestination.displayName), ex);
					}
				};
				if (executor==null)
					removeMember.run();
				else
					executor.submit(removeMember);
			}
		}
	}
	
	/**
	 * Creates the missing group at the destination AAD
	 * @param groupId Unique identification of the existing group at the source AAD
	 * @param sourceGraphClient Object to be used as a client of the source AAD, given the provided credentials
	 * @param destinationGraphClient Object to be used as a client of the destination AAD, given the provided credentials
	 */
	public Group createMissingGroup(String groupId, GraphServiceClient<Request> sourceGraphClient, GraphServiceClient<Request> destinationGraphClient) {
		Level level = (previewMode) ? Level.INFO : Level.FINE;
		
		Group groupAtSource = sourceGraphClient.groups(groupId)
				.buildRequest(DEFAULT_REQUEST_OPTIONS)
				.select("description,displayName,id,isAssignableToRole,mailEnabled,securityEnabled,mailNickname")
				.get();

		if (log.isLoggable(level))
			log.log(level, String.format("Creating new group at destination: %s", groupAtSource.displayName));

		Group groupAtDestination = new Group();
		groupAtDestination.description = groupAtSource.description;
		groupAtDestination.displayName = groupAtSource.displayName;
		groupAtDestination.isAssignableToRole = groupAtSource.isAssignableToRole;
		groupAtDestination.mailEnabled = groupAtSource.mailEnabled;
		groupAtDestination.securityEnabled = groupAtSource.securityEnabled;
		groupAtDestination.mailNickname = groupAtSource.mailNickname;
		
		if (!previewMode) {
			groupAtDestination = destinationGraphClient.groups()
				.buildRequest()
				.post(groupAtDestination);
			countGroupsCreated.increment();
		}
		
		return groupAtDestination;
	}
	
	/**
	 * Includes the user as a new member of the group at the destination AAD
	 * @param user User object defined at the destination AAD
	 * @param group Group object defined at the destination AAD (only the 'id' and 'displayName' fields are needed)
	 * @param destinationGraphClient Object to be used as a client of the destination AAD, given the provided credentials
	 */
	public void addMember(User user, Group group, GraphServiceClient<Request> destinationGraphClient) {
		Level level = (previewMode) ? Level.INFO : Level.FINE;
		if (log.isLoggable(level))
			log.log(level, String.format("Including user %s as member of group %s", user.displayName, group.displayName));
		if (previewMode)
			return;
		destinationGraphClient.groups(group.id).members().references()
			.buildRequest()
			.post(user);		
		countMembersCreated.increment();
	}

	/**
	 * Removes the user as a member of the group at the destination AAD
	 * @param user User object defined at the destination AAD (only the 'id' and the 'displayName' fields are needed)
	 * @param group Group object defined at the destination AAD (only the 'id' and the 'displayName' fields are needed)
	 * @param destinationGraphClient Object to be used as a client of the destination AAD, given the provided credentials
	 */
	public void removeMember(User user, Group group, GraphServiceClient<Request> destinationGraphClient) {
		Level level = (previewMode) ? Level.INFO : Level.FINE;
		if (log.isLoggable(level))
			log.log(level, String.format("Excluding user %s as member of group %s", user.displayName, group.displayName));
		if (previewMode)
			return;
		destinationGraphClient.groups(group.id).members(user.id).reference()
			.buildRequest()
			.delete();
		countMembersRemoved.increment();
	}

	public long getCountGroups() {
		return countGroups.longValue();
	}
	
	public long getCountUsersMembers() {
		return countUsersMembers.longValue();
	}
	
	public long getCountMissingGroupsAtDestination() {
		return countMissingGroupsAtDestination.longValue();
	}
	
	public long getCountErrorsCreatingGroups() {
		return countErrorsCreatingGroups.longValue();
	}
	
	public long getCountGroupsCreated() {
		return countGroupsCreated.longValue();
	}
	
	public long getCountMissingUsersAtDestination() {
		return missingUsersAtDestination.size();
	}
	
	public long getCountMembersCreated() {
		return countMembersCreated.longValue();
	}
	
	public long getCountErrorsCreatingMembers() {
		return countErrorsCreatingMembers.longValue();
	}
	
	public long getCountMembersRemoved() {
		return countMembersRemoved.longValue();
	}
	
	public long getCountErrorsRemovingMembers() {
		return countErrorsRemovingMembers.longValue();
	}
	
	/**
	 * Returns the time elapsed (in milliseconds)
	 */
	public long getTimeElapsedMS() {
		return timestampEnd - timestampStart;
	}
	
	/**
	 * Returns a summary text with the results of the last execution of this procedure
	 */
	public String getSummary() {
		StringBuilder summary = new StringBuilder();
		summary.append("Time elapsed: ").append(getTimeElapsedMS()).append(" ms\n");
		summary.append("Count of groups at source: ").append(getCountGroups()).append("\n");
		summary.append("Count of missing groups at destination: ").append(getCountMissingGroupsAtDestination()).append("\n");
		if (createMissingGroups) {
			summary.append("Count of missing groups created: ").append(getCountGroupsCreated()).append("\n");
			summary.append("Count of missing groups not created due to errors: ").append(getCountErrorsCreatingGroups()).append("\n");
		}
		summary.append("Count of users members at source: ").append(getCountUsersMembers()).append("\n");
		summary.append("Count of missing users at destination: ").append(getCountMissingUsersAtDestination()).append("\n");
		summary.append("Count of users members created at destination: ").append(getCountMembersCreated()).append("\n");
		summary.append("Count of users members not created due to errors: ").append(getCountErrorsCreatingMembers()).append("\n");
		if (removeMembers) {
			summary.append("Count of users members removed at destination: ").append(getCountMembersRemoved()).append("\n");
			summary.append("Count of users members not removed due to errors: ").append(getCountErrorsRemovingMembers()).append("\n");
		}
		return summary.toString();
	}
}
