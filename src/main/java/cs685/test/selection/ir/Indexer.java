package cs685.test.selection.ir;

import java.io.File;
import java.io.IOException;
import java.io.StringReader;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Stack;

import org.apache.lucene.analysis.standard.StandardAnalyzer;
import org.apache.lucene.document.Document;
import org.apache.lucene.document.TextField;
import org.apache.lucene.index.DirectoryReader;
import org.apache.lucene.index.IndexReader;
import org.apache.lucene.index.IndexWriter;
import org.apache.lucene.index.IndexWriterConfig;
import org.apache.lucene.index.IndexWriterConfig.OpenMode;
import org.apache.lucene.queryparser.classic.ParseException;
import org.apache.lucene.queryparser.classic.QueryParser;
import org.apache.lucene.search.IndexSearcher;
import org.apache.lucene.search.Query;
import org.apache.lucene.search.TopDocs;
import org.apache.lucene.store.Directory;
import org.apache.lucene.store.FSDirectory;

import com.github.javaparser.JavaParser;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.NodeList;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.Parameter;
import com.github.javaparser.ast.comments.Comment;
import com.github.javaparser.ast.expr.AnnotationExpr;
import com.github.javaparser.ast.stmt.BlockStmt;
import com.github.javaparser.ast.stmt.Statement;

import hudson.FilePath;

public class Indexer {
	private static final String INDEX_DIR = "index";
	
	private IndexWriter writer;
	private IndexReader reader;
	private IndexSearcher searcher;
	private QueryParser parser;
	private Map<Integer, String> testDocuments;

	/**
	 * 
	 * @param root
	 * @throws IOException
	 * @throws InterruptedException 
	 */
	public Indexer(FilePath root) throws IOException, InterruptedException {
		// Create a map of document ID to test method name
		testDocuments = new HashMap<Integer, String>();
		// New index
		StandardAnalyzer standardAnalyzer = new StandardAnalyzer();
		
		Directory indexDir = FSDirectory.open(Paths.get(new File(INDEX_DIR).getAbsolutePath()));
		IndexWriterConfig config = new IndexWriterConfig(standardAnalyzer);
		// Check if we can load from file
		if (DirectoryReader.indexExists(indexDir)) {
			writer = new IndexWriter(indexDir, config);
			return; // Don't re-add all Test files to index
		}
		
		// Create a writer
		writer = new IndexWriter(indexDir, config);

		config.setOpenMode(OpenMode.CREATE);
		// Iterate over the entire directory
		Stack<FilePath> toProcess = new Stack<>();
        toProcess.push(root);
        int docId = 0;
        while (!toProcess.isEmpty()) {
            FilePath path = toProcess.pop();
            if (path.isDirectory()) {
            	// If directory, add all content within it to the stack
                toProcess.addAll(path.list());
            } else if (path.getName().endsWith(".java")) {
            	// If a java file, parse it.
            	CompilationUnit cu = JavaParser.parse(path.read());
				// Get the class name
				List<ClassOrInterfaceDeclaration> classes = cu.findAll(ClassOrInterfaceDeclaration.class);
				for (ClassOrInterfaceDeclaration classDeclaration : classes) {
					// Get class name and parse it
					String className = classDeclaration.getName().asString(); // Keep class name unparsed for future use
					// Parse class name to add to NL documents
					String parsedClassName = parseCamelCase(className).toLowerCase();
					parsedClassName = InformationRetriever.removeStopwords(parsedClassName);
					List<MethodDeclaration> methods = classDeclaration.getChildNodesByType(MethodDeclaration.class);
					for (MethodDeclaration method : methods) {
						boolean isTestCase = false;
						NodeList<AnnotationExpr> annotations = method.getAnnotations();
						for (AnnotationExpr annotation : annotations) {
							if (annotation.getNameAsString().equals("Test")) {
								isTestCase = true;
								break;
							}
						}
						if (isTestCase) {
							StringBuilder methodContent = new StringBuilder();
							// Get the method's class name
							methodContent.append(parsedClassName);
							methodContent.append("\n");
							// Get method's name
							String methodName = method.getName().asString(); // The real method name is needed later
							String methodNameParsed = parseCamelCase(methodName).toLowerCase();
							methodContent.append(InformationRetriever.removeStopwords(methodNameParsed));
							methodContent.append("\n");
							// Get method's parameters
							for (Parameter param : method.getParameters()) {
								String params = param.toString().replaceAll("[^A-Za-z ]", " ").trim();
								params = parseCamelCase(params).toLowerCase();
								methodContent.append(InformationRetriever.removeStopwords(params));
								methodContent.append(" ");
							}
							// Get method's documentation
							Optional<Comment> javadocComment = method.getComment();
							if (javadocComment.isPresent()) {
								String javadoc = javadocComment.get().getContent();
								javadoc = javadoc.replaceAll("[^A-Za-z ]", " ").trim();
								javadoc = parseCamelCase(javadoc).toLowerCase();
								methodContent.append(InformationRetriever.removeStopwordsAndKeywords(javadoc));
								methodContent.append("\n");
							}
							// Get method's content
							Optional<BlockStmt> methodBlock = method.getBody();
							if (methodBlock.isPresent()) {
								for (Statement statement : methodBlock.get().getStatements()) {
									String statementContent = statement.toString().replaceAll("[^A-Za-z ]", " ").trim();
									statementContent = parseCamelCase(statementContent).toLowerCase();
									methodContent.append(InformationRetriever.removeStopwordsAndKeywords(statementContent));
									methodContent.append("\n");
								}
							}
							// Add the method's document to the writer
							Document document = new Document();
							// document.add(new TextField(method.getName().asString() + " content", new StringReader(methodContent.toString())));
							document.add(new TextField("content", new StringReader(methodContent.toString())));
							System.out.println("Doc=" + docId + ", name=" + method.getName().asString());
							writer.addDocument(document);
							if (testDocuments.containsValue(methodName)) {
								System.out.println("***WARNING***: Found an overloaded test method: [" + methodName + "]");
							}
							testDocuments.put(docId, className + "." + methodName);
							docId++;
						}
					}
				}
			} else {
				System.out.println("Ignoring non-Java file: [" + path.getName() + "]");
			}
		}

		writer.commit(); // commits pending documents to index
		reader = DirectoryReader.open(indexDir);
		searcher = new IndexSearcher(reader);
		parser = new QueryParser("content", standardAnalyzer);
	}

	public TopDocs query(String queryString, int numResults) throws ParseException, IOException {
		Query query = parser.parse(queryString);
		return searcher.search(query, numResults);
	}

	public String getDocumentById(int id) {
		return testDocuments.get(id);
	}

	public void close() throws IOException {
		writer.close();
		reader.close();
	}

	public static String parseCamelCase(String s) {
		StringBuilder result = new StringBuilder();
		// Regex from https://stackoverflow.com/questions/7593969/regex-to-split-camelcase-or-titlecase-advanced
		for (String w : s.split("(?<!(^|[A-Z]))(?=[A-Z])|(?<!^)(?=[A-Z][a-z])")) {
			result.append(w);
			result.append(" ");
		}
		return result.toString();
	}
}