package cs685.test.generation;

import java.io.BufferedWriter;
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
import com.github.javaparser.ast.visitor.VoidVisitorAdapter;

import hudson.Extension;
import hudson.FilePath;
import hudson.Launcher;
import hudson.model.AbstractBuild;
import hudson.model.AbstractProject;
import hudson.model.BuildListener;
import hudson.tasks.BuildWrapper;
import hudson.tasks.BuildWrapperDescriptor;

public class TestGenerationBuildWrapper extends BuildWrapper {

    private static final String REPORT_TEMPLATE_PATH = "/stats.html";
    private static final String PROJECT_NAME_VAR = "$PROJECT_NAME$";
    private static final String CLASS_METHOD_CONTENT_VAR = "$CLASS_METHOD_CONTENT$";

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
                TestGeneration stats = buildStats(build.getWorkspace());
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
                return super.tearDown(build, listener);
            }
        };
    }

    private static TestGeneration buildStats(FilePath root) throws IOException, InterruptedException {
    	HashMap<String, List<String>> classMap = new HashMap<String, List<String>>();

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
        return new TestGeneration(classMap);
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
        content = content.replace(PROJECT_NAME_VAR, projectName);
        StringBuilder tableContent = new StringBuilder();
        for (String className : stats.getClassNames()) {
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
        content = content.replace(CLASS_METHOD_CONTENT_VAR, tableContent.toString());
        
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
