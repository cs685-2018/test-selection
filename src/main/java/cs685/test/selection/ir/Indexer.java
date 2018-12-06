package cs685.test.selection.ir;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.Stack;

import org.apache.commons.io.IOUtils;
import org.apache.lucene.analysis.Analyzer;
import org.apache.lucene.analysis.standard.StandardAnalyzer;
import org.apache.lucene.analysis.util.CharArraySet;
import org.apache.lucene.document.Document;
import org.apache.lucene.document.TextField;
import org.apache.lucene.index.DirectoryReader;
import org.apache.lucene.index.IndexWriter;
import org.apache.lucene.index.IndexWriterConfig;
import org.apache.lucene.index.Term;
import org.apache.lucene.queryparser.classic.ParseException;
import org.apache.lucene.queryparser.classic.QueryParser;
import org.apache.lucene.search.IndexSearcher;
import org.apache.lucene.search.Query;
import org.apache.lucene.search.ScoreDoc;
import org.apache.lucene.search.TermQuery;
import org.apache.lucene.search.TopDocs;
import org.apache.lucene.search.TopScoreDocCollector;
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
import jenkins.model.Jenkins;

/**
 * 
 * @author Ryan
 *
 */
public class Indexer {
	private static final String CLASS_NAME_FIELD = "class_name";
	private static final String METHOD_NAME_FIELD = "method_name";
	private static final String CONTENT_FIELD = "content";
	
	private static final org.apache.lucene.document.Field.Store STORE = org.apache.lucene.document.Field.Store.YES;
	
	private File indexPath = new File(Jenkins.getInstance().getRootDir(), "luceneIndex");
	private File indexProjectPath;
	private final String projectName;
	
	private final Directory index;
	private final IndexWriter dbWriter;
	private final Analyzer analyzer;
	private DirectoryReader reader;

	/**
	 * 
	 * @param root
	 * @throws IOException
	 * @throws InterruptedException 
	 */
	public Indexer(FilePath root, String projectName, Set<String> filesToUpdate) throws IOException, InterruptedException {
		this.projectName = projectName;
		this.indexProjectPath = new File(this.indexPath, this.projectName);
		this.analyzer = new StandardAnalyzer(CharArraySet.EMPTY_SET);
		this.index = FSDirectory.open(this.indexProjectPath.toPath());
		
		// Check if we can load from file, so we can add all test documents or only update test documents
		boolean indexExists = false;
		if (DirectoryReader.indexExists(this.index)) {
			System.out.println("We already have an index!");
			indexExists = true;
		} else {
			System.out.println("No index exists, creating a new one!");
		}
		
		IndexWriterConfig config = new IndexWriterConfig(analyzer);
		this.dbWriter = new IndexWriter(this.index, config);
		updateReader();
		buildDocuments(root, filesToUpdate, indexExists);
	}
	
	/**
	 * 
	 * @param root
	 * @param filesToUpdate
	 * @param indexExists
	 * @throws IOException
	 * @throws InterruptedException
	 */
	private void buildDocuments(FilePath root, Set<String> filesToUpdate, boolean indexExists) throws IOException, InterruptedException {
		
		System.out.println("Our files to update are: ");
		for (String s : filesToUpdate) {
			System.out.println("\t"+s);
		}
		
		// Iterate over the entire directory
		Stack<FilePath> toProcess = new Stack<>();
        toProcess.push(root);
        int ignoredFiles = 0;
        int testCaseMethods = 0;
        int nonTestCaseMethods = 0;
        while (!toProcess.isEmpty()) {
            FilePath path = toProcess.pop();
            if (path.isDirectory()) {
            	// If directory, add all content within it to the stack
                toProcess.addAll(path.list());
            } else if (path.getName().endsWith(".java")) {
            	// If a java file, parse it.
            	CompilationUnit cu = JavaParser.parse(path.read());
				// Get the class names
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
							// Create a document based on the current test method
							Document document = new Document();
							// Add the method's class name
							document.add(new TextField(CLASS_NAME_FIELD, className, STORE));
							// Add the method's name
							document.add(new TextField(METHOD_NAME_FIELD, methodName, STORE));
							// Add the method's content
							document.add(new TextField(CONTENT_FIELD, methodContent.toString(), STORE));
							
							// TODO: add checks for deleted files
							if (indexExists) {
								// Calculate the filepath of the current file
								String currFilePathSplit[] = path.getRemote().split(this.projectName);
								String currFilePath = null;
								if (currFilePathSplit.length != 2) {
									System.out.println("ERROR: File not in project's directory?");
								} else {
									currFilePath = currFilePathSplit[1].substring(1); // remove the leading forward slash
								}
								if (filesToUpdate.contains(currFilePath)) {
									System.out.println("Our index already contains " +currFilePath + " and it needs to be updated!");
									// Remove the old document, add the new one
									removeDoc(className, methodName);
									storeDoc(document);
								} // Else, we will skip adding documents that don't need to be updated
								else {
									System.out.println("No need to update at this time. " + currFilePath);
								}
							} else {
								// Add all documents if we didn't have an index
								System.out.println("No existing index, adding document...");
								this.dbWriter.addDocument(document);
							}
							
							testCaseMethods++;
						} else {
							nonTestCaseMethods++;
						}
					}
				}
			} else {
				ignoredFiles++;
			}
		}
        
        // Make sure all documents are committed
        updateReader();
        
        System.out.println(Integer.toString(testCaseMethods) + " test case methods found.");
        System.out.println(Integer.toString(nonTestCaseMethods) + " other methods found.");
        System.out.println(Integer.toString(ignoredFiles) + " non-Java files found.");

        System.out.println("Indexed documents:");
        for (int i = 0; i < reader.maxDoc(); i++) {
        	//dont care about deletions for now...
        	Document d = this.reader.document(i);
        	System.out.println("Document["+Integer.toString(i)+"]: class=[" + d.get(CLASS_NAME_FIELD)+"], method=["+
        			d.get(METHOD_NAME_FIELD)+"]");
        }
	}

	/**
	 * 
	 */
	public synchronized void close() {
		IOUtils.closeQuietly(this.dbWriter);
		IOUtils.closeQuietly(this.index);
	}
	
	/**
	 * 
	 * @throws IOException
	 */
	private void updateReader() throws IOException {
        this.dbWriter.commit();
        this.reader = DirectoryReader.open(this.index);
    }
	
	/**
	 * 
	 * @param query
	 * @param n
	 * @return
	 */
	public List<TestCase> getHits(String query, int n) {
        List<TestCase> testCases = new ArrayList<TestCase>();
        try {
        	System.out.println("Querying for <=" + Integer.toString(n) + " test cases with query: [" + query + "]");
            QueryParser queryParser = new QueryParser(CONTENT_FIELD, this.analyzer);
            Query q = queryParser.parse(query);

            IndexSearcher searcher = new IndexSearcher(this.reader);
            TopScoreDocCollector collector = TopScoreDocCollector.create(n);
            
            searcher.search(q, collector);
            ScoreDoc[] hits = collector.topDocs().scoreDocs;
            System.out.println("Found " + Integer.toString(hits.length) + " hits");
            
            // Do we care about score ordering here?
            List<Document> docs = new ArrayList<Document>();

            for (ScoreDoc hit : hits) {
                Document doc = searcher.doc(hit.doc);
                docs.add(doc);
            }
            System.out.println("Created " + Integer.toString(docs.size()) + " documents from hits");
            for (Document doc : docs) {
                String className = doc.get(CLASS_NAME_FIELD);
                String methodName = doc.get(METHOD_NAME_FIELD);
                String content = doc.get(CONTENT_FIELD);
                
                System.out.println("Document found: (className=[" + className + "], methodName=[" + methodName + "])");

                testCases.add(new TestCase(className, methodName, content));
            }
        } catch (ParseException e) {
            // Do nothing
        } catch (IOException e) {
            // Do nothing
        }
        return testCases;
    }

	/**
	 * 
	 * @param doc
	 * @throws IOException
	 */
    public void storeDoc(Document doc) throws IOException {
    	this.dbWriter.addDocument(doc);
    	updateReader();
    }

    /**
     * 
     * @param className
     * @param methodName
     * @return
     */
    public Document removeDoc(String className, String methodName) {//typeSignature
        try {
        	// Create terms for class and method names
        	Term classTerm = new Term(CLASS_NAME_FIELD, className);
        	Term methodTerm = new Term(METHOD_NAME_FIELD, className);
            IndexSearcher searcher = new IndexSearcher(reader);
            
            // Search for a matching document
            // TODO: for overloaded methods, this may cause problems
            // TODO: may need to add another document field for parameter types/signatures
            TopDocs search = searcher.search(new TermQuery(classTerm), 1);
            Document doc = null;
            if (search.scoreDocs.length > 0) {
            	// Delete matching documents
                doc = searcher.doc(search.scoreDocs[0].doc);
                dbWriter.deleteDocuments(classTerm, methodTerm);
                updateReader();
            }
            return doc;
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * 
     * @param s
     * @return
     */
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