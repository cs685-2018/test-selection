package cs685.test.selection;

import java.io.BufferedWriter;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import javax.annotation.Nonnull;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;

import org.apache.lucene.queryparser.classic.ParseException;
import org.apache.maven.shared.invoker.DefaultInvocationRequest;
import org.apache.maven.shared.invoker.DefaultInvoker;
import org.apache.maven.shared.invoker.InvocationOutputHandler;
import org.apache.maven.shared.invoker.InvocationRequest;
import org.apache.maven.shared.invoker.InvocationResult;
import org.apache.maven.shared.invoker.Invoker;
import org.apache.maven.shared.invoker.MavenInvocationException;
import org.kohsuke.stapler.DataBoundConstructor;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.SAXException;

import cs685.test.selection.ir.InformationRetriever;
import hudson.Extension;
import hudson.FilePath;
import hudson.Launcher;
import hudson.model.AbstractBuild;
import hudson.model.AbstractProject;
import hudson.model.BuildListener;
import hudson.tasks.BuildWrapper;
import hudson.tasks.BuildWrapperDescriptor;
import io.reflectoring.diffparser.api.DiffParser;
import io.reflectoring.diffparser.api.UnifiedDiffParser;
import io.reflectoring.diffparser.api.model.Diff;

/**
 * 
 * @author Ryan, Dan
 *
 */
public class TestSelectionBuildWrapper extends BuildWrapper {

	private static final String PLUGIN_DISPLAY_NAME = "Use Test Selection";
    private static final String REPORT_TEMPLATE_PATH = "/results.html";
    private static final String PROJECT_NAME_VAR = "$PROJECT_NAME$";
    private static final String SELECTED_TESTS_VAR = "$SELECTED_TESTS$";
    private static final String MAVEN_OUTPUT_VAR = "$MAVEN_OUTPUT$";
    private static final String SUREFIRE_REPORTS_VAR = "$SUREFIRE_REPORTS$";
    private static final String SUREFIRE_DIRECTORY = "target/surefire-reports";
    
    @DataBoundConstructor
    public TestSelectionBuildWrapper() {
    }

    @Override
    public Environment setUp(AbstractBuild build, final Launcher launcher, BuildListener listener) {
        return new Environment() {
            @Override
            public boolean tearDown(AbstractBuild build, BuildListener listener)
              throws IOException, InterruptedException
            {
            	// TODO: Maven findbugs believes build.getWorkspace returns (or potentially returns) null at some point
            	// Error given is "NP_NULL_ON_SOME_PATH_FROM_RETURN_VALUE"
            	if (build == null) {
            		throw new NullPointerException("TestGenerationBuildWrapper.setUp.tearDown: AbstractBuild object is null.");
            	}
            	else if (build.getWorkspace() == null) {
            		throw new NullPointerException("TestGenerationBuildWrapper.setUp.tearDown: AbstractBuild.getWorkspace() object is null.");
            	}
            	
            	// Get the selected tests
            	// TODO: change n (5) to be a tunable parameter by the user
            	int n = 5;
            	Set<String> selectedTests = null;
                try {
					selectedTests = getSelectedTests(build.getWorkspace(), build, n);
				} catch (ParseException e) {
					System.out.println("Error while parsing Java project:");
					e.printStackTrace();
				}
                
                // Split selected tests up by class
                Map<String, List<String>> selectedTestsMapper = new HashMap<String, List<String>>();
                for (String selectedTest : selectedTests) {
                	String[] selectedTestSplit = selectedTest.split("\\.");
                	if (selectedTestSplit.length != 2) {
                		System.out.println("Error with selected test <class>.<method> name: [" + selectedTest + "]");
                	} else {
                		String className = selectedTestSplit[0];
                		String methodName = selectedTestSplit[1];
                		if (selectedTestsMapper.containsKey(className)) {
                			selectedTestsMapper.get(className).add(methodName);
                		} else {
                			List<String> methods = new ArrayList<String>();
                			methods.add(methodName);
                			selectedTestsMapper.put(className, methods);
                		}
                	}
                }
                
                // Generate the Maven test selection string
                StringBuilder testSelection = new StringBuilder();
                int i = 0;
                // Builds an output log of the mavenOutput
				StringBuilder mavenOutput = new StringBuilder();
		        String absolutePath = build.getWorkspace().getRemote();
		        System.out.println(absolutePath);
		        for (String className : selectedTestsMapper.keySet()) {
		        	// Code for using maven-surefire 2.19+
		        	testSelection.append(className).append("#");
		        	testSelection.append(String.join("+", selectedTestsMapper.get(className)));
		        	// Separate classes by comma (should work for maven-surefire 2.19+)
		        	if (i+1 < selectedTestsMapper.keySet().size()) {
		        		testSelection.append(",");	
		        	}
		        	i++;
		        }
		        System.out.println("Test selection string=[" + testSelection.toString() + "]");
		        String command = "test -DfailIfNoTests=false -Dtest=" + testSelection.toString();
		        try {
					mavenOutput.append(runCommand(command, new File(absolutePath)));
				} catch (MavenInvocationException e) {
					mavenOutput.append("<font color=\"red\">MavenInvocationException</font>");
					e.printStackTrace();
				}
		        
		        // Generate a report and analyze the maven surefire reports
				String report = generateReport(build.getProject().getDisplayName(), 
						selectedTestsMapper, 
						mavenOutput.toString(),
						build.getWorkspace().getRemote());
			
				// Old method to generate the report
                File artifactsDir = build.getArtifactsDir();
                if (!artifactsDir.isDirectory()) {
                    boolean success = artifactsDir.mkdirs();
                    if (!success) {
                        listener.getLogger().println("Can't create artifacts directory at "
                          + artifactsDir.getAbsolutePath());
                    }
                }
                String path = artifactsDir.getCanonicalPath() + REPORT_TEMPLATE_PATH;
                try (BufferedWriter writer = new BufferedWriter(new OutputStreamWriter(new FileOutputStream(path),
                  StandardCharsets.UTF_8))) {
                    writer.write(report);
                    writer.close();
                }
	
                return super.tearDown(build, listener);
			}
        };
    }

    /**
     * Returns the selected tests from the InformationRetriever at the given FilePath
     * @param root
     * @param build
     * @param n
     * @return
     * @throws IOException
     * @throws InterruptedException
     * @throws ParseException
     */
    private static Set<String> getSelectedTests(FilePath root, AbstractBuild build, int n) throws IOException, InterruptedException, ParseException {
    	FilePath workspaceDir = root;
    	TestSelection testSelector = new TestSelection(workspaceDir, build);
    	
    	// Get the list of diffs
    	DiffParser parser = new UnifiedDiffParser();
        InputStream in = new ByteArrayInputStream(testSelector.getDifferences().getBytes());
        List<Diff> diffs = parser.parse(in);
        System.out.println("We parsed out " + Integer.toString(diffs.size()) + " diffs!");
        
        // Create the information retriever
    	InformationRetriever ir = new InformationRetriever(root, diffs, build.getWorkspace().getName());
    	
        Set<String> selectedTests = ir.getTestDocuments(n);
        ir.close();
        return selectedTests;
    }

    /**
     * Generates the contents of the report results.html file
     * 
     * @param projectName
     * @param selectedTests
     * @return
     * @throws IOException
     * @throws ParserConfigurationException 
     * @throws SAXException 
     */
    private static String generateReport(
    		String projectName, Map<String, List<String>> selectedTests, String mavenOutput, String workspaceDir)
    		throws IOException {
        ByteArrayOutputStream bOut = new ByteArrayOutputStream();
        try (InputStream in = TestSelectionBuildWrapper.class.getResourceAsStream(REPORT_TEMPLATE_PATH)) {
            byte[] buffer = new byte[1024];
            int read;
            while ((read = in.read(buffer)) >= 0) {
                bOut.write(buffer, 0, read);
            }
        }
        String content = new String(bOut.toByteArray(), StandardCharsets.UTF_8);
        content = content.replace(PROJECT_NAME_VAR, projectName);
        StringBuilder selectedTestsContent = new StringBuilder();
        for (String className : selectedTests.keySet()) {
        	selectedTestsContent.append("<tr><td>");
        	selectedTestsContent.append(className);
        	selectedTestsContent.append("</td><td>");
        	int i = 0;
        	for (String methodName : selectedTests.get(className)) {
        		selectedTestsContent.append(methodName);
        		if (i+1 < selectedTests.get(className).size()) {
        			selectedTestsContent.append("<br/>");
        		}
        		i++;
        	}
        	selectedTestsContent.append("</td></tr>\n");
        }
        content = content.replace(SELECTED_TESTS_VAR, selectedTestsContent);

        // Parse the surefire-reports XML files
    	File surefireDir = new File(workspaceDir, SUREFIRE_DIRECTORY);
    	StringBuilder surefireContent = new StringBuilder();
    	try {
	        if (surefireDir.isDirectory()) {
	        	DocumentBuilderFactory dbFactory = DocumentBuilderFactory.newInstance();
	        	DocumentBuilder dbBuilder;
				dbBuilder = dbFactory.newDocumentBuilder();
	        	File[] surefireReports = surefireDir.listFiles();
	        	for (File surefireReport : surefireReports) {
	        		// Check if this is a TEST xml report file
	        		String surefireReportFilename = surefireReport.getName();
	        		// Build the report for each test suite
	        		// See the following link for the surefire report Xml schema:
	        		// https://maven.apache.org/surefire/maven-surefire-plugin/xsd/surefire-test-report-3.0.xsd
	        		if (surefireReportFilename.contains("TEST") && surefireReportFilename.endsWith(".xml")) {
	        			//org.w3c.dom.Document
	        			Document doc = dbBuilder.parse(surefireReport);
	        			doc.getDocumentElement().normalize();
	        			NodeList testSuites = doc.getElementsByTagName("testsuite");
	        			// Iterate over all test suites, should just be 1
	        			for (int j = 0; j < testSuites.getLength(); j++) {
	        				Node testSuite = testSuites.item(j);
	        				if (testSuite.getNodeType() == Node.ELEMENT_NODE) {
	        					Element testSuiteElement = (Element)testSuite;
	        					// Get test suite attributes
	        					String className = testSuiteElement.getAttribute("name");
	        					String totalTime = testSuiteElement.getAttribute("time");
	        					int tests = Integer.parseInt(testSuiteElement.getAttribute("tests"));
	        					int errors = Integer.parseInt(testSuiteElement.getAttribute("errors"));
	        					int skipped = Integer.parseInt(testSuiteElement.getAttribute("skipped"));
	        					int failures = Integer.parseInt(testSuiteElement.getAttribute("failures"));
	        					surefireContent.append("<p><strong><em>").append(className)
	        						.append("</strong></em>: ").append(tests).append(" tests ran");
	        					if (errors > 0) {
	        						surefireContent.append(", <font color=\"red\">").append(errors).append("</font> errors");
	        					}
	        					if (skipped > 0) {
	        						surefireContent.append(", <font color=\"orange\">").append(skipped).append("</font> skipped");
	        					}
	        					if (failures > 0) {
	        						surefireContent.append(", <font color=\"red\">").append(failures).append("</font> failures");
	        					}
	        					surefireContent.append("</p><p>Total time: ").append(totalTime)
	        						.append(" s</p><p><table border=\"1\"><tr><td><strong>Test case</strong></td>")
	        						.append("<td><strong>time (s)</strong></td><td><strong>Result</strong></td></tr>\n");
	        					// Get all test cases 
	        					NodeList testCases = testSuiteElement.getElementsByTagName("testcase");
	        					for (int k = 0; k < testCases.getLength(); k++) {
	        						Node testCase = testCases.item(k);
	        						if (testCase.getNodeType() == Node.ELEMENT_NODE) {
	        							Element testCaseElement = (Element)testCase;
	        							String testCaseMethod = testCaseElement.getAttribute("name");
	        							String testCaseTime = testCaseElement.getAttribute("time");
	        							// Only checking for <skipped>, <error>, and <failure>
	        							NodeList skippedNode = testCaseElement.getElementsByTagName("skipped");
	        							NodeList errorNode = testCaseElement.getElementsByTagName("error");
	        							NodeList failureNode = testCaseElement.getElementsByTagName("failure");
	        							surefireContent.append("<tr><td>").append(testCaseMethod).append("</td><td>")
	        								.append(testCaseTime).append("</td><td><font color=\"");
	        							if (errorNode.getLength() > 0 || failureNode.getLength() > 0) {
	        								surefireContent.append("red\">").append("FAILED");
	        							}
	        							else if (skippedNode.getLength() > 0) {
	        								surefireContent.append("orange\">").append("SKIPPED");
	        							} else {
	        								surefireContent.append("green\">").append("SUCCESS");
	        							}
	        							surefireContent.append("</font></td></tr>");
	        						} else {
	        							System.out.println("ERROR: testCase node is not of type ELEMENT_NODE, but type: " + testCase.getNodeType());
	        						}
	        					}
	        					surefireContent.append("</table></p>");
	        				} else {
	        					System.out.println("ERROR: testSuite node is not of type ELEMENT_NODE, but type: " + testSuite.getNodeType());
	        				}
	        			}
	        		}
	        	}
	        } else {
	        	System.out.println("Could not find directory for surefire reports, expected: " + surefireDir.getAbsolutePath());
	        	surefireContent.append("<font color=\"red\">ERROR</font>: Could not find surefire reports directory");
	        }
    	} catch (SAXException e) {
    		System.out.println("ERROR: failed parsing maven-surefire xml reports: SAXException");
    		surefireContent.append("<font color=\"red\">Failed parsing maven-surefire xml reports: SAXException</font>");
			e.printStackTrace();
		} catch (ParserConfigurationException e) {
			System.out.println("ERROR: failed parsing maven-surefire xml reports: ParserConfigurationException");
			surefireContent.append("<font color=\"red\">Failed parsing maven-surefire xml reports: ParserConfigurationException</font>");
			e.printStackTrace();
		}
        
        content = content.replace(SUREFIRE_REPORTS_VAR, surefireContent.toString());
        
        // Display Maven output
        content = content.replace(MAVEN_OUTPUT_VAR, mavenOutput);
                
        return content;
    }

    /**
     * Invokes the given Maven command from a pom.xml file within the working directory
     * @param mavenCommand
     * @param workingDirectory
     * @return
     * @throws MavenInvocationException
     */
	public String runCommand(String mavenCommand, File workingDirectory) throws MavenInvocationException {	
		InvocationRequest request = new DefaultInvocationRequest();
		request.setPomFile(new File(workingDirectory, "pom.xml"));

		List<String> goals= new ArrayList<String>(); goals.add(mavenCommand);
		
		request.setGoals(Collections.singletonList(mavenCommand));
	 	Invoker invoker = new DefaultInvoker();			
		final StringBuilder mavenOutput = new StringBuilder();
		invoker.setOutputHandler(new InvocationOutputHandler() {
		    public void consumeLine(String line) {
		        mavenOutput.append(line).append("<br/>");
		    }
		});
		// You can find the Maven home by calling "mvn --version"
		invoker.setMavenHome(new File("/usr/share/maven"));
		try {
		    InvocationResult invocationResult = invoker.execute(request);

		    // Process maven output
		    System.out.println(mavenOutput);
		    if (invocationResult.getExitCode() != 0) {
		        System.out.println("Maven invocation error on command [" + mavenCommand + "] in directory: " + workingDirectory.getAbsolutePath());
		        return null;
		    }
		} catch (MavenInvocationException e) {
		    e.printStackTrace();
		}
		return mavenOutput.toString();	
	    }

    @Extension
    public static final class DescriptorImpl extends BuildWrapperDescriptor {

        @Override
        public boolean isApplicable(AbstractProject<?, ?> item) {
            return true;
        }

        @Nonnull
        @Override
        public String getDisplayName() {
        	// Set the display name within the Jenkins project configuration
            return PLUGIN_DISPLAY_NAME;
        }
    }
}
