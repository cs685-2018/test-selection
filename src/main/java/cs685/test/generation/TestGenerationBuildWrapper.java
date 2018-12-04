package cs685.test.generation;

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
import java.util.HashMap;
import java.util.List;
import java.util.Stack;

import javax.annotation.Nonnull;

import org.kohsuke.stapler.DataBoundConstructor;

import com.github.javaparser.JavaParser;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.Parameter;
import com.github.jenkins.lastchanges.model.CommitChanges;

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
import io.reflectoring.diffparser.api.model.Hunk;
import io.reflectoring.diffparser.api.model.Line;

public class TestGenerationBuildWrapper extends BuildWrapper {

    private static final String REPORT_TEMPLATE_PATH = "/stats.html";
    private static final String PROJECT_NAME_VAR = "$PROJECT_NAME$";
    private static final String CLASS_METHOD_CONTENT_VAR = "$CLASS_METHOD_CONTENT$";
    private static final String GIT_COMMITS_VAR = "$GIT_COMMITS$";
    private static final String GIT_DIFFS_VAR = "$GIT_DIFFS$";
    private static final String DIFF_PARSER_VAR = "$DIFF_PARSER$";
    

    @DataBoundConstructor
    public TestGenerationBuildWrapper() {
    }

    @Override
    public Environment setUp(AbstractBuild build, final Launcher launcher, BuildListener listener) {
        return new Environment() {
            @Override
            public boolean tearDown(AbstractBuild build, BuildListener listener)
              throws IOException, InterruptedException
            {
            	// Maven findbugs believes build.getWorkspace returns (or potentially returns) null at some point
            	// Error given is "NP_NULL_ON_SOME_PATH_FROM_RETURN_VALUE"
            	if (build == null) {
            		throw new NullPointerException("TestGenerationBuildWrapper.setUp.tearDown: AbstractBuild object is null.");
            	}
            	else if (build.getWorkspace() == null) {
            		throw new NullPointerException("TestGenerationBuildWrapper.setUp.tearDown: AbstractBuild.getWorkspace() object is null.");
            	}
                TestGeneration stats = buildStats(build.getWorkspace(), build);
                String report = generateReport(build.getProject().getDisplayName(), stats);
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
                //maven code is gonna go here
				MavenCli cli = new MavenCli();
				cli.doMain(new String[]{"clean", "install"}, "project_dir", System.out, System.out);
				
				
				return super.tearDown(build, listener);
			}
        };
    }

    private static TestGeneration buildStats(FilePath root, AbstractBuild build) throws IOException, InterruptedException {
    	HashMap<String, List<String>> classMap = new HashMap<String, List<String>>();
    	FilePath workspaceDir = root;
    	System.out.println("***TestGenerationBuildWrapper.buildStats.root (FilePath): " + workspaceDir);
        Stack<FilePath> toProcess = new Stack<>();
        toProcess.push(root);
        while (!toProcess.isEmpty()) {
            FilePath path = toProcess.pop();
            if (path.isDirectory()) {
            	// If directory, add all content within it to the stack
                toProcess.addAll(path.list());
            } else if (path.getName().endsWith(".java")) {
            	// If a java file, parse it.
            	CompilationUnit cu = JavaParser.parse(path.read());
            	
            	String className = null;
            	// Hopefully only 1 class per file?
            	for (ClassOrInterfaceDeclaration classDecl : cu.findAll(ClassOrInterfaceDeclaration.class)) {
            		className = classDecl.getName().asString();
            	}
            	
            	if (!classMap.containsKey(className)) {
            		classMap.put(className, new ArrayList<String>());
            	}
            	
            	for (MethodDeclaration method : cu.findAll(MethodDeclaration.class)) {
            		StringBuilder sb = new StringBuilder();
            		sb.append(method.getName());
                    sb.append("(");
                    boolean firstParam = true;
                    for (Parameter param : method.getParameters()) {
                        if (firstParam) {
                            firstParam = false;
                        } else {
                            sb.append(", ");
                        }
                        sb.append(param.getType().toString());
                        if (param.isVarArgs()) {
                            sb.append("...");
                        }
                    }
                    sb.append(")");
            		classMap.get(className).add(sb.toString());
            	}
            }
        }
        return new TestGeneration(classMap, workspaceDir, build);
    }

    private static String generateReport(String projectName, TestGeneration stats) throws IOException {
        ByteArrayOutputStream bOut = new ByteArrayOutputStream();
        try (InputStream in = TestGenerationBuildWrapper.class.getResourceAsStream(REPORT_TEMPLATE_PATH)) {
            byte[] buffer = new byte[1024];
            int read;
            while ((read = in.read(buffer)) >= 0) {
                bOut.write(buffer, 0, read);
            }
        }
        String content = new String(bOut.toByteArray(), StandardCharsets.UTF_8);
        content =v content.replace(PROJECT_NAME_VAR, projectName);
        StringBuilder tableContent = new StringBuilder();
        for (String className : stats.getClassNames()) {
        	if (className.toLowerCase().contains("test")) {
	        	List<String> methodNames = stats.getMethodNames(className);
	        	if (methodNames.size() > 0) {
		        	tableContent.append("<tr>\n");
		        	tableContent.append("<td rowspan=\"");
		        	tableContent.append(methodNames.size());
		        	tableContent.append("\">");
		        	tableContent.append(className);
		        	tableContent.append("</td>\n<td>");
		        	tableContent.append(methodNames.get(0));
		        	tableContent.append("</td>\n</tr>\n");
					
					
		        	for (int i = 1; i < methodNames.size(); i++) {
		        		tableContent.append("<tr>\n<td>");
		        		tableContent.append(methodNames.get(i));
		        		tableContent.append("</td>\n</tr>\n");
		        	}
					
	        	} else {
	        		tableContent.append("<tr>\n<td>");
	        		tableContent.append(className);
	        		tableContent.append("</td>\n<td>No methods found</td>\n</tr>\n");
	        	}
        	}
        }
        content = content.replace(CLASS_METHOD_CONTENT_VAR, tableContent.toString());
        
        StringBuilder commitContent = new StringBuilder();
        List<CommitChanges> commits = stats.getChanges();
        for (CommitChanges commit : commits) {
        	commitContent.append("<table border=\"1\">\n<tr><td>toString()</td><td>");
        	commitContent.append(commit.toString());
        	commitContent.append("</td></tr>\n<tr><td>getChanges()</td><td>");
        	commitContent.append(commit.getChanges());
        	commitContent.append("</td></tr>\n<tr><td>getEscapedDiff()</td><td>");
        	commitContent.append(commit.getEscapedDiff());
        	commitContent.append("</td></tr>\n</table>\n");
        }
        content = content.replace(GIT_COMMITS_VAR, commitContent.toString());
        
        content = content.replace(GIT_DIFFS_VAR, stats.getDifferences() + "\n");
        
        DiffParser parser = new UnifiedDiffParser();
        InputStream in = new ByteArrayInputStream(stats.getDifferences().getBytes());
        List<Diff> diffs = parser.parse(in);
        StringBuilder diffContent = new StringBuilder();
        diffContent.append("<table border=\"1\">\n");
        for (Diff diff : diffs) {
            diffContent.append("<tr><td>From file</td><td>");
        	diffContent.append(diff.getFromFileName());
            diffContent.append("</td></tr>\n<tr><td>To file</td><td>");
            diffContent.append(diff.getToFileName());
            diffContent.append("</td></tr>\n<tr><td>Header lines</td><td>");
            List<String> headerLines = diff.getHeaderLines();
            for (String headerLine : headerLines) {
                diffContent.append(headerLine + "<br/>");
            }
            diffContent.append("</td></tr>\n<tr><td>Hunks</td><td>");
            int i = 0;
            for (Hunk hunk : diff.getHunks()) {
                diffContent.append("<strong>Hunk " + i + "</strong>:<br/>");
                for (Line line : hunk.getLines()) {
                	if (line.getLineType() == Line.LineType.TO || line.getLineType() == Line.LineType.FROM) {
                		diffContent.append("<font color=\"");
                		if (line.getLineType() == Line.LineType.TO) {
                			diffContent.append("green");
                		} else {
                			diffContent.append("red");
                		}
                		diffContent.append("\"><em>" + line.getContent() + "</em></font><br/>");
                	}
                }
                i++;
            }
            diffContent.append("</td></tr>\n");
        }
        diffContent.append("</table>\n");
        
        content=content.replace(DIFF_PARSER_VAR, diffContent.toString());
        
        return content;
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
            return "Use test generation";
        }

    }

}
