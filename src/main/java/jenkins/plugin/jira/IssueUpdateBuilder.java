package jenkins.plugin.jira;


import hudson.Extension;
import hudson.Launcher;
import hudson.Util;
import hudson.model.BuildListener;
import hudson.model.AbstractBuild;
import hudson.model.AbstractProject;
import hudson.scm.ChangeLogSet;
import hudson.scm.ChangeLogSet.Entry;
import hudson.tasks.BuildStepDescriptor;
import hudson.tasks.BuildStepMonitor;
import hudson.tasks.Builder;
import integration.services.jira.JiraSoapService;
import integration.services.jira.RemoteComment;
import integration.services.jira.RemoteFieldValue;
import integration.services.jira.RemoteIssue;
import integration.services.jira.RemoteNamedObject;

import java.io.IOException;
import java.io.PrintWriter;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;


import jenkins.model.Jenkins;



import net.sf.json.JSONObject;

import org.apache.commons.lang.StringUtils;
import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.StaplerRequest;

import common.SOAPSession;

public class IssueUpdateBuilder extends Builder{

	private static final Logger LOGGER = Logger.getLogger(IssueUpdateBuilder.class.getName());

	private final String issuePattern;
	private final String comments;
	private final int numOfPreBuild;
	private final String assignForAction;
	private final String statusForTransition;

	@DataBoundConstructor
	public IssueUpdateBuilder(String issuePattern, String statusForTransition, String assignForAction, int numOfPreBuild, String comments) {
		this.issuePattern = issuePattern;
		this.numOfPreBuild = numOfPreBuild;
		this.comments = comments;
		this.statusForTransition = statusForTransition;
		this.assignForAction = assignForAction;
	}

	public String getIssuePattern() {
		return issuePattern;
	}

	/**
	 * @return the comment
	 */
	 public String getComments() {
		 return comments;
	 }

	 public int getNumOfPreBuild() {
		 return numOfPreBuild;
	 }

	 public String getAssignForAction() {
		 return assignForAction;
	 }

	 public String getStatusForTransition() {
		 return statusForTransition;
	 }

	 /**
	  * Performs the actual update based on job configuration.
	  */
	 @Override
	 public boolean perform(AbstractBuild<?, ?> build, Launcher launcher, BuildListener listener) throws InterruptedException, IOException {
		 PrintWriter out = new PrintWriter(listener.getLogger());
		 out.println("[INFO] Updating associated JIRA issue(s)...");
		 String realComment = Util.fixEmptyAndTrim(build.getEnvironment(listener).expand(comments));
		 String realIssuePattern = Util.fixEmptyAndTrim(build.getEnvironment(listener).expand(issuePattern));
		 Pattern pattern = Pattern.compile(realIssuePattern);
		 Set<String> issueKeys = new LinkedHashSet<String>();

		 int index = -1;
		 while(index++ < this.numOfPreBuild && build!=null){

			 ChangeLogSet<? extends Entry> changeSet = build.getChangeSet();
			 for(Object e : changeSet.getItems()){
				 ChangeLogSet.Entry entry = (ChangeLogSet.Entry) e;
				 String comments = entry.getMsg();
				 Matcher m = pattern.matcher(comments);
				 if(m.find()){
					 issueKeys.add(m.group(0));
				 }
			 }

			 build = build.getPreviousBuild();
		 }
		 if(issueKeys.isEmpty()){
			 out.println("[INFO] No issue key extracted.");
			 out.close();
			 return true;
		 }
		 String issueKeysToChange = StringUtils.join(issueKeys, ",");

		 out.println("[INFO] Extracted issues key(s) -> ("+issueKeysToChange+")");

		 JiraConfig.DescriptorImpl descriptor = (JiraConfig.DescriptorImpl) Jenkins.getInstance().getDescriptor(JiraConfig.class);
		 JiraConfig jiraConfig = descriptor.getJiraConfig();
		 try {
			 SOAPSession jira = jiraConfig.createSession();
			 String token = jira.getAuthenticationToken();
			 JiraSoapService jiraSoapService = jira.getJiraSoapService();


			 RemoteIssue[] issuesFromJqlSearch = jiraSoapService.getIssuesFromJqlSearch(token, "id in ("+issueKeysToChange+") and status = \""+statusForTransition+"\"", issueKeys.size());
			 if(issuesFromJqlSearch.length == 0){
				 out.println("[INFO] No Jira issue in \""+statusForTransition+"\" status");
				 out.close();
				 return true;
			 }
			 for(RemoteIssue r : issuesFromJqlSearch){
				 RemoteComment[] allComments = jiraSoapService.getComments(token, r.getKey());
				 boolean commented = false;
				 for(RemoteComment rc : allComments){
					 if(realComment.equalsIgnoreCase(rc.getBody())){
						 commented = true;
					 }
				 }

				 if(commented){
					 continue;
				 }

				 out.println("[INFO] Comments added -> "+realComment);
				 RemoteComment comment =  new RemoteComment();
				 comment.setBody(realComment);
				 jiraSoapService.addComment(token, r.getKey(), comment);
				 RemoteNamedObject[] availableActions = jiraSoapService.getAvailableActions(token, r.getKey());
				 for(RemoteNamedObject action : availableActions){
					 if(action.getName().equalsIgnoreCase(assignForAction)){
						 out.println("[INFO] Progress issue ["+r.getKey()+"] -> ["+assignForAction+"]");
						 jiraSoapService.progressWorkflowAction(token, r.getKey(), action.getId(), new RemoteFieldValue[]{});
					 }
				 }

			 }
		 } catch (Exception e) {
			 LOGGER.log(Level.SEVERE, e.getMessage());
			 out.println("[ERROR] "+e.getMessage());
			 out.close();
			 return true;
		 }
		 out.close();
		 return true;
	 }

	 public BuildStepMonitor getRequiredMonitorService() {
		 return BuildStepMonitor.BUILD;
	 }

	 @Override
	 public DescriptorImpl getDescriptor() {
		 return (DescriptorImpl) super.getDescriptor();
	 }

	 @Extension
	 public static final class DescriptorImpl extends BuildStepDescriptor<Builder> {

		 private String issuePattern;
		 private String comments;

		 public DescriptorImpl() {
			 super(IssueUpdateBuilder.class);
			 load();
		 }

		 @Override
		 public String getDisplayName() {
			 return "Update JIRA Issue(s)";
		 }

		 @Override
		 public boolean configure(StaplerRequest req, JSONObject formData) {
			 issuePattern = (String) formData.get("issuePattern");
			 comments = (String) formData.get("comments");
			 save();
			 return true;
		 }

		 public String getIssuePattern() {
			 return issuePattern;
		 }

		 public void setIssuePattern(String issuePattern) {
			 this.issuePattern = issuePattern;
		 }

		 public String getComments() {
			 return comments;
		 }

		 public void setComments(String comments) {
			 this.comments = comments;
		 }

		 @Override
		 public boolean isApplicable(Class<? extends AbstractProject> jobType) {
			 return true;
		 }
	 }

}
