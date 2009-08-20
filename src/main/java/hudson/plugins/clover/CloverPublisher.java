package hudson.plugins.clover;

import hudson.Launcher;
import hudson.FilePath;
import hudson.Util;
import hudson.remoting.VirtualChannel;
import hudson.plugins.clover.results.ProjectCoverage;
import hudson.plugins.clover.targets.CoverageTarget;
import hudson.plugins.clover.targets.CoverageMetric;
import hudson.model.*;
import hudson.tasks.Publisher;
import org.kohsuke.stapler.StaplerRequest;

import java.io.*;
import java.util.Set;
import java.util.List;

/**
 * Clover {@link Publisher}.
 *
 * @author Stephen Connolly
 */
public class CloverPublisher extends Publisher {

    private final String cloverReportDir;

    private CoverageTarget healthyTarget;
    private CoverageTarget unhealthyTarget;
    private CoverageTarget failingTarget;

    /**
     * @param cloverReportDir
     * @stapler-constructor
     */
    public CloverPublisher(String cloverReportDir) {
        this.cloverReportDir = cloverReportDir;
        this.healthyTarget = new CoverageTarget();
        this.unhealthyTarget = new CoverageTarget();
        this.failingTarget = new CoverageTarget();
    }

    public String getCloverReportDir() {
        return cloverReportDir;
    }

    /**
     * Getter for property 'healthyTarget'.
     *
     * @return Value for property 'healthyTarget'.
     */
    public CoverageTarget getHealthyTarget() {
        return healthyTarget;
    }

    /**
     * Setter for property 'healthyTarget'.
     *
     * @param healthyTarget Value to set for property 'healthyTarget'.
     */
    public void setHealthyTarget(CoverageTarget healthyTarget) {
        this.healthyTarget = healthyTarget;
    }

    /**
     * Getter for property 'unhealthyTarget'.
     *
     * @return Value for property 'unhealthyTarget'.
     */
    public CoverageTarget getUnhealthyTarget() {
        return unhealthyTarget;
    }

    /**
     * Setter for property 'unhealthyTarget'.
     *
     * @param unhealthyTarget Value to set for property 'unhealthyTarget'.
     */
    public void setUnhealthyTarget(CoverageTarget unhealthyTarget) {
        this.unhealthyTarget = unhealthyTarget;
    }

    /**
     * Getter for property 'failingTarget'.
     *
     * @return Value for property 'failingTarget'.
     */
    public CoverageTarget getFailingTarget() {
        return failingTarget;
    }

    /**
     * Setter for property 'failingTarget'.
     *
     * @param failingTarget Value to set for property 'failingTarget'.
     */
    public void setFailingTarget(CoverageTarget failingTarget) {
        this.failingTarget = failingTarget;
    }

    /**
     * Gets the directory where the Clover Report is stored for the given project.
     */
    /*package*/
    static File getCloverXmlReport(AbstractBuild<?, ?> build) {
        return new File(build.getRootDir(), "clover.xml");
    }


    public boolean perform(AbstractBuild<?, ?> build, Launcher launcher, BuildListener listener) throws InterruptedException {


        final File buildRootDir = build.getRootDir(); // should this top level?
        final FilePath buildTarget = new FilePath(buildRootDir);
        final FilePath workspace = build.getParent().getWorkspace();
        FilePath coverageReportDir = workspace.child(cloverReportDir);
        try {
            listener.getLogger().println("Publishing Clover coverage report...");

            // search one deep for the report dir, if it doesn't exist. 
            if (!coverageReportDir.exists()) {
                coverageReportDir = findOneDirDeep(workspace, cloverReportDir);
            }
            
            // if the build has failed, then there's not
            // much point in reporting an error
            final boolean buildFailure = build.getResult().isWorseOrEqualTo(Result.FAILURE);
            final boolean missingReport = !coverageReportDir.exists();
            
            if (buildFailure && missingReport) {
                listener.getLogger().println("No Clover report will be published due to a " + (buildFailure ? "Build Failure" : "missing report"));
                return true;
            }

            final boolean htmlExists = copyHtmlReport(coverageReportDir, buildTarget, listener);
            final boolean xmlExists = copyXmlReport(coverageReportDir, buildTarget, listener);

            if (htmlExists) { 
                // only add the HTML build action, if the HTML report is available
                build.getActions().add(new CloverHtmlBuildAction(buildTarget));
            }
            processCloverXml(build, listener, coverageReportDir, buildTarget);

        } catch (IOException e) {
            Util.displayIOException(e, listener);
            e.printStackTrace(listener.fatalError("Unable to copy coverage from " + coverageReportDir + " to " + buildTarget));
            build.setResult(Result.FAILURE);
        }


        return true;
    }

    /**
     * Process the clover.xml from the build directory. The clover.xml must have been already copied to the build dir.
     * 
     */
    private void processCloverXml(AbstractBuild<?, ?> build, BuildListener listener, FilePath coverageReport, FilePath buildTarget) throws InterruptedException {
        String workspacePath = "";
        try {
            workspacePath = build.getParent().getWorkspace().act(new FilePath.FileCallable<String>() {
                public String invoke(File file, VirtualChannel virtualChannel) throws IOException {
                    try {
                        return file.getCanonicalPath();
                    } catch (IOException e) {
                        return file.getAbsolutePath();
                    }
                }
            });
        } catch (IOException e) {
        }
        if (!workspacePath.endsWith(File.separator)) {
            workspacePath += File.separator;
        }

        final File cloverXmlReport = getCloverXmlReport(build);
        if (cloverXmlReport.exists()) {
            listener.getLogger().println("Publishing Clover coverage results...");
            ProjectCoverage result = null;
            try {
                result = CloverCoverageParser.parse(cloverXmlReport, workspacePath);
            } catch (IOException e) {
                Util.displayIOException(e, listener);
                e.printStackTrace(listener.fatalError("Unable to copy coverage from " + coverageReport + " to " + buildTarget));
                build.setResult(Result.FAILURE);
            }
            final CloverBuildAction action = CloverBuildAction.load(build, workspacePath, result, healthyTarget, unhealthyTarget);

            build.getActions().add(action);
            Set<CoverageMetric> failingMetrics = failingTarget.getFailingMetrics(result);
            if (!failingMetrics.isEmpty()) {
                listener.getLogger().println("Code coverage enforcement failed for the following metrics:");
                for (CoverageMetric metric : failingMetrics) {
                    listener.getLogger().println("    " + metric);
                }
                listener.getLogger().println("Setting Build to unstable.");
                build.setResult(Result.UNSTABLE);
            }

        } else {
            flagMissingCloverXml(listener, build);
        }
    }

    private boolean copyXmlReport(FilePath coverageReport, FilePath buildTarget, BuildListener listener) throws IOException, InterruptedException {
        // check one directory deep for a clover.xml, if there is not one in the coverageReport dir already
        // the clover auto-integration saves clover reports in: clover/${ant.project.name}/clover.xml
        final FilePath cloverXmlPath = findOneDirDeep(coverageReport, "clover.xml");
        if (cloverXmlPath.exists()) {
            cloverXmlPath.copyTo(buildTarget.child("clover.xml"));
            listener.getLogger().println("Published clover Xml report.");
            return true;
        } else {
            listener.getLogger().println("Clover xml file does not exist at: " + cloverXmlPath);
            return false;
        }
    }

    private boolean copyHtmlReport(FilePath coverageReport, FilePath buildTarget, BuildListener listener) throws IOException, InterruptedException {
        // Copy the HTML coverage report
        final FilePath htmlIndexHtmlPath = findOneDirDeep(coverageReport, "index.html");
        if (htmlIndexHtmlPath.exists()) {
            final FilePath htmlDirPath = htmlIndexHtmlPath.getParent();
            htmlDirPath.copyRecursiveTo("**/*", buildTarget);
            listener.getLogger().println("Published Clover HTML report.");
            return true;
        } else {
            return false;
        }
    }

    /**
     * Searches the current directory and its immediate children directories for filename.
     * The first occurence is returned.
     * @param startDir the dir to start searching in
     * @param filename the filename to search for
     * @return the path of filename
     * @throws IOException
     * @throws InterruptedException
     */
    private FilePath findOneDirDeep(final FilePath startDir, final String filename) throws IOException, InterruptedException {

        FilePath dirContainingFile = startDir;
        if (!dirContainingFile.child(filename).exists()) {
            // use the first directory with filename in it
            final List<FilePath> dirs = dirContainingFile.listDirectories();
            if (dirs != null) {
                for (FilePath dir : dirs) {
                    if (dir.child(filename).exists()) {
                        dirContainingFile = dir;
                        break;
                    }
                }
            }
        }
        return dirContainingFile.child(filename);
    }

    private void flagMissingCloverXml(BuildListener listener, AbstractBuild<?, ?> build) {
        listener.getLogger().println("Could not find '" + cloverReportDir + "/clover.xml'.  Did you generate " +
                "the XML report for Clover?");
    }


    @Override
    public Action getProjectAction(Project project) {
        return new CloverProjectAction(project);
    }

    public Descriptor<Publisher> getDescriptor() {
        // see Descriptor javadoc for more about what a descriptor is.
        return DESCRIPTOR;
    }

    /**
     * Descriptor should be singleton.
     */
    public static final DescriptorImpl DESCRIPTOR = new DescriptorImpl();

    /**
     * Descriptor for {@link CloverPublisher}. Used as a singleton. The class is marked as public so that it can be
     * accessed from views.
     * <p/>
     * <p/>
     * See <tt>views/hudson/plugins/clover/CloverPublisher/*.jelly</tt> for the actual HTML fragment for the
     * configuration screen.
     */
    public static final class DescriptorImpl extends Descriptor<Publisher> {
        DescriptorImpl() {
            super(CloverPublisher.class);
        }

        /**
         * This human readable name is used in the configuration screen.
         */
        public String getDisplayName() {
            return "Publish Clover Coverage Report";
        }


        public boolean configure(StaplerRequest req) throws FormException {
            req.bindParameters(this, "clover.");
            save();
            return super.configure(req);    //To change body of overridden methods use File | Settings | File Templates.
        }

        /**
         * Creates a new instance of {@link CloverPublisher} from a submitted form.
         */
        public CloverPublisher newInstance(StaplerRequest req) throws FormException {
            CloverPublisher instance = req.bindParameters(CloverPublisher.class, "clover.");
            req.bindParameters(instance.failingTarget, "cloverFailingTarget.");
            req.bindParameters(instance.healthyTarget, "cloverHealthyTarget.");
            req.bindParameters(instance.unhealthyTarget, "cloverUnhealthyTarget.");
            // start ugly hack
            if (instance.healthyTarget.isEmpty()) {
                instance.healthyTarget = new CoverageTarget(70, 80, 80);
            }
            // end ugly hack
            return instance;
        }
    }
}
