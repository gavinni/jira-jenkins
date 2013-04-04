package jenkins.plugin.jira;

import hudson.Extension;
import hudson.Util;
import hudson.model.AbstractDescribableImpl;
import hudson.model.Describable;
import hudson.model.AbstractProject;
import hudson.model.Descriptor;
import hudson.model.Hudson;
import hudson.util.FormValidation;
import integration.services.jira.RemoteIssue;

import java.io.IOException;
import java.net.MalformedURLException;
import java.net.URL;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

import javax.servlet.ServletException;
import javax.xml.rpc.ServiceException;

import jenkins.model.Jenkins;

import net.sf.json.JSONObject;

import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.QueryParameter;
import org.kohsuke.stapler.StaplerRequest;

import common.SOAPSession;


public class JiraConfig implements Describable<JiraConfig> {
	
	private static final Logger LOGGER = Logger.getLogger(JiraConfig.class.getName());

    public final URL url;

	/**
     * User name needed to login. Optional.
     */
    public final String userName;

    /**
     * Password needed to login. Optional.
     */
    public final String password;
    
    /**
     * Used to guard the computation of {@link #projects}
     */
    private transient Lock configUpdateLock = new ReentrantLock();
    
    @DataBoundConstructor
    public JiraConfig(URL url, String userName, String password) {
        if(!url.toExternalForm().endsWith("/"))
            try {
                url = new URL(url.toExternalForm()+"/");
            } catch (MalformedURLException e) {
                throw new AssertionError(e); // impossible
            }
        this.url = url;
        this.userName = Util.fixEmpty(userName);
        this.password = Util.fixEmpty(password);
    }
    
	public URL getUrl() {
		return url;
	}

	public String getUserName() {
		return userName;
	}

	public String getPassword() {
		return password;
	}

	@SuppressWarnings("unchecked")
	public Descriptor<JiraConfig> getDescriptor() {
		return Jenkins.getInstance().getDescriptorOrDie(JiraConfig.class);
	}

    public String getName() {
        return url.toExternalForm();
    }

    public SOAPSession createSession() throws IOException, ServiceException {
        SOAPSession soapSession = new SOAPSession(url);
        soapSession.connect(userName, password);
        return soapSession;
    }

    /**
     * Computes the URL to the given issue.
     */
    public URL getUrl(RemoteIssue issue) throws IOException {
        return getUrl(issue.getKey());
    }

    /**
     * Computes the URL to the given issue.
     */
    public URL getUrl(String key) throws MalformedURLException {
        return new URL(url, "browse/" + key.toUpperCase());
    }
    
    protected Object readResolve() {
        configUpdateLock = new ReentrantLock();
        return this;
    }

    @Extension
    public static class DescriptorImpl extends Descriptor<JiraConfig> {
    	
    	private URL url;
    	private String userName;
    	private String password;
    	
    	public DescriptorImpl(){
    		super(JiraConfig.class);
			load();
    	}
    	
        @Override
        public String getDisplayName() {
            return "JIRA Configuration";
        }
        
        @Override
        public boolean configure(StaplerRequest req, JSONObject json) throws FormException {
            try {
				url = new URL((String)json.get("url"));
			} catch (MalformedURLException e) {
				throw new FormException("Invalid URL", "url field");
			}
            userName = (String)json.get("userName");
            password = (String)json.get("password");
            
            save();
            return true;
        }
        
        public URL getUrl() {
			return url;
		}

		public void setUrl(URL url) {
			this.url = url;
		}

		public String getUserName() {
			return userName;
		}

		public void setUserName(String userName) {
			this.userName = userName;
		}

		public String getPassword() {
			return password;
		}

		public void setPassword(String password) {
			this.password = password;
		}

		/**
         * Checks if the JIRA URL is accessible and exists.
         */
        public FormValidation doUrlCheck(@QueryParameter final String value)
                throws IOException, ServletException {
            // this can be used to check existence of any file in any URL, so
            // admin only
            if (!Hudson.getInstance().hasPermission(Hudson.ADMINISTER))
                return FormValidation.ok();

            return new FormValidation.URLCheck() {
                @Override
                protected FormValidation check() throws IOException,
                        ServletException {
                    String url = Util.fixEmpty(value);
                    if (url == null) {
                        return FormValidation.error("Invalid Jira URL");
                    }

                    // call the wsdl uri to check if the jira soap service can be reached
                    try {
                          if (!findText(open(new URL(url)), "Atlassian JIRA"))
                              return FormValidation.error("Soap Service is unreachable");

                        URL soapUrl = new URL(new URL(url), "rpc/soap/jirasoapservice-v2?wsdl");
                        if (!findText(open(soapUrl), "wsdl:definitions"))
                              return FormValidation.error("Invalid WSDL");

                          return FormValidation.ok();
                    } catch (IOException e) {
                        LOGGER.log(Level.WARNING,
                                "Unable to connect to " + url, e);
                        return handleIOException(url, e);
                    }
                }
            }.check();
        }

        public FormValidation doCheckUserPattern(@QueryParameter String value) throws IOException {
            String userPattern = Util.fixEmpty(value);
            if (userPattern == null) {// userPattern not entered yet
                return FormValidation.ok();
            }
            try {
                Pattern.compile(userPattern);
                return FormValidation.ok();
            } catch (PatternSyntaxException e) {
                return FormValidation.error(e.getMessage());
            }
        }
        
        

        /**
         * Checks if the user name and password are valid.
         */
        public FormValidation doValidate(@QueryParameter String userName,
                                          @QueryParameter String url,
                                          @QueryParameter String password) throws IOException {
            url = Util.fixEmpty(url);
            if (url == null) {// URL not entered yet
                return FormValidation.error("No URL given");
            }
            JiraConfig site = new JiraConfig(new URL(url), userName, password);
            try {
                site.createSession();
                return FormValidation.ok("Success");
            } catch (Exception e) {
                LOGGER.log(Level.WARNING, "Failed to login to JIRA at " + url, e);
                return FormValidation.error(e.getMessage());
            } 
        }
        
        public JiraConfig getJiraConfig(){
        	return new JiraConfig(url, userName, password);
        }
    }


}
