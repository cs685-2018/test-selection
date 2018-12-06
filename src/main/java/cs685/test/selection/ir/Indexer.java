package cs685.test.selection.ir;

import java.io.File;
import java.io.IOException;
import java.io.StringReader;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Stack;

import org.apache.commons.io.IOUtils;
import org.apache.lucene.analysis.Analyzer;
import org.apache.lucene.analysis.CharArraySet;
import org.apache.lucene.analysis.standard.StandardAnalyzer;
import org.apache.lucene.document.Document;
import org.apache.lucene.document.StringField;
import org.apache.lucene.document.TextField;
import org.apache.lucene.index.DirectoryReader;
import org.apache.lucene.index.IndexReader;
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
import hudson.model.Run;
import jenkins.model.Jenkins;

public class Indexer {
	private static final String INDEX_DIR = "index";
	private static final int MAX_HITS = 100;
	private static final String CLASS_NAME_FIELD = "class_name";
	private static final String METHOD_NAME_FIELD = "method_name";
	private static final String CONTENT_FIELD = "content";
	private static final String ID_FIELD = "id";
	private static final String[] FIELDS = new String[] {CLASS_NAME_FIELD, METHOD_NAME_FIELD, CONTENT_FIELD, ID_FIELD };
	
	private File indexPath = new File(Jenkins.getInstance().getRootDir(), "luceneIndex");
	
	private final Directory index;
	private final IndexWriter dbWriter;
	private final Analyzer analyzer;
	private DirectoryReader reader;
	//private IndexReader idxReader;
	//private IndexSearcher searcher;
	//private QueryParser parser;
	//private Map<Integer, String> testDocuments;

	/**
	 * 
	 * @param root
	 * @throws IOException
	 * @throws InterruptedException 
	 */
	public Indexer(FilePath root) throws IOException, InterruptedException {
		// TODO: figure out how to iterate through indexed documents
			// get the list of (potential) documents modified by the diffs
				// document = className.methodName
				// this would only matter when a test class was directly modified
			// for each modified document, re-index it (parse with Javaparser)
		// TODO: load docIds from indexer directly
		
		analyzer = new StandardAnalyzer(CharArraySet.EMPTY_SET);
		index = FSDirectory.open(indexPath.toPath());
		IndexWriterConfig config = new IndexWriterConfig(analyzer);
		dbWriter = new IndexWriter(index, config);
		updateReader();
		buildDocuments(root);
	}
	
	private void buildDocuments(FilePath root) throws IOException, InterruptedException {
		
		// Create a map of document ID to test method name
		//testDocuments = new HashMap<Integer, String>();
		// New index
		
//		Directory indexDir = FSDirectory.open(Paths.get(new File(INDEX_DIR).getAbsolutePath()));
		
		// Check if we can load from file
		/*if (DirectoryReader.indexExists(indexDir)) {
			writer = new IndexWriter(indexDir, config);
			return; // Don't re-add all Test files to index
		}*/
		
		// Create a writer
		//writer = new IndexWriter(indexDir, config);

		//config.setOpenMode(OpenMode.CREATE);
		
		
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
							// Add the method's document to the writer
							Document document = new Document();
							// document.add(new TextField(method.getName().asString() + " content", new StringReader(methodContent.toString())));
							//id
							String id = "blah";
							document.add(new TextField(ID_FIELD, new StringReader(id)));
							//classname
							document.add(new TextField(CLASS_NAME_FIELD, new StringReader(className)));
							//methodname
							document.add(new TextField(METHOD_NAME_FIELD, new StringReader(methodName)));
							//content
							document.add(new TextField(CONTENT_FIELD, new StringReader(methodContent.toString())));
							
							dbWriter.addDocument(document);
							/*if (testDocuments.containsValue(methodName)) {
								System.out.println("***WARNING***: Found an overloaded test method: [" + methodName + "]");
							}*/
							//testDocuments.put(docId, className + "." + methodName);
							//docId++;
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
        
        System.out.println(Integer.toString(testCaseMethods) + " test case methods found.");
        System.out.println(Integer.toString(nonTestCaseMethods) + " other methods found.");
        System.out.println(Integer.toString(ignoredFiles) + " non-Java files found.");

        for (int i = 0; i < reader.maxDoc(); i++) {
        	//dont care about deletions for now...
        	Document d = reader.document(i);
        	System.out.println("Document["+Integer.toString(i)+"]: class=[" + d.get(CLASS_NAME_FIELD)+"], method=["+
        			d.get(METHOD_NAME_FIELD)+"]");
        }
        
        
		//writer.commit(); // commits pending documents to index
		//reader = DirectoryReader.open(indexDir);
		//searcher = new IndexSearcher(reader);
		//parser = new QueryParser("content", standardAnalyzer);
	}

	/*public TopDocs query(String queryString, int numResults) throws ParseException, IOException {
		Query query = parser.parse(queryString);
		return searcher.search(query, numResults);
	}*/

	/*public String getDocumentById(int id) {
		return testDocuments.get(id);
	}*/

	// NEW METHODS
	public synchronized void close() {
		IOUtils.closeQuietly(dbWriter);
		IOUtils.closeQuietly(index);
	}
	
	private void updateReader() throws IOException {
        dbWriter.commit();
        reader = DirectoryReader.open(index);
    }
	
	public List<TestCase> getHits(String query, int n) {//, boolean includeHighlights) {
        List<TestCase> luceneSearchResultImpl = new ArrayList<TestCase>();
        try {
            QueryParser queryParser = new QueryParser(CONTENT_FIELD, analyzer);
            Query q = queryParser.parse(query);

            IndexSearcher searcher = new IndexSearcher(reader);
            TopScoreDocCollector collector = TopScoreDocCollector.create(n);
            //QueryTermScorer scorer = new QueryTermScorer(q);
            //Highlighter highlighter = new Highlighter(new SimpleHTMLFormatter(), scorer);
            searcher.search(q, collector);
            ScoreDoc[] hits = collector.topDocs().scoreDocs;
            // This originally ordered documents by score and build time (?)
            /*TreeMultimap<Float, Document> docs = TreeMultimap.create(new Comparator<Float>() {
            	@Override
                public int compare(Float o1, Float o2) {
                    return o2.compareTo(o1);
                }
            });*/
            // Do we care about score ordering here?
            List<Document> docs = new ArrayList<Document>();

            for (ScoreDoc hit : hits) {
                Document doc = searcher.doc(hit.doc);
                //docs.put(hit.score, doc);
                docs.add(doc);
            }
            //for (Document doc : docs.values()) {
            for (Document doc : docs) {
            	// not sure what this does
                /*String[] bestFragments = EMPTY_ARRAY;
                if (includeHighlights) {
                    try {
                        bestFragments = highlighter.getBestFragments(analyzer, CONSOLE.fieldName,
                                doc.get(CONSOLE.fieldName), MAX_NUM_FRAGMENTS);
                    } catch (InvalidTokenOffsetsException e) {
                        LOGGER.warn("Failed to find bestFragments", e);
                    }
                }*/
            	/*
                BallColor buildIcon = BallColor.GREY;
                String colorName = doc.get(BALL_COLOR.fieldName);
                if (colorName != null) {
                    buildIcon = BallColor.valueOf(colorName);
                }
                */
                String className = doc.get(CLASS_NAME_FIELD);
                String methodName = doc.get(METHOD_NAME_FIELD);
                String content = doc.get(CONTENT_FIELD);
                String id = doc.get(ID_FIELD);
                //String projectName = doc.get(PROJECT_NAME.fieldName);
                //String buildNumber = doc.get(BUILD_NUMBER.fieldName);

                /*String url;
                if (doc.get(URL.fieldName) != null) {
                    url = doc.get(URL.fieldName);
                } else {
                    url = "/job/" + projectName + "/" + buildNumber + "/";
                }*/

                //luceneSearchResultImpl.add(new FreeTextSearchItemImplementation(projectName, buildNumber, bestFragments, buildIcon.getImage(), url));
                luceneSearchResultImpl.add(new TestCase(Integer.valueOf(id), className, methodName, content));
            }
        } catch (ParseException e) {
            // Do nothing
        } catch (IOException e) {
            // Do nothing
        }
        return luceneSearchResultImpl;
    }

	// Not using this...
	/*
    private MultiFieldQueryParser getQueryParser() {
        MultiFieldQueryParser queryParser = new MultiFieldQueryParser(getAllDefaultSearchableFields(), analyzer) {
            @Override
            protected Query getRangeQuery(String field, String part1, String part2, boolean startInclusive,
                    boolean endInclusive) throws ParseException {
                if (field != null && getIndex(field).numeric) {
                    Long min = getWithDefault(part1, null);
                    Long max = getWithDefault(part2, null);
                    return NumericRangeQuery.newLongRange(field, min, max, true, true);
                } else if (field != null) {
                    return new TermQuery(new Term(field));
                }
                return super.getRangeQuery(null, part1, part2, startInclusive, endInclusive);
            }
        };
        queryParser.setDefaultOperator(QueryParser.Operator.AND);
        queryParser.setLocale(LOCALE);
        queryParser.setAnalyzeRangeTerms(true);
        queryParser.setLowercaseExpandedTerms(true);
        return queryParser;
    }
    */

    //@Override
    public void storeDoc(final Run<?, ?> run, Document oldDoc) throws IOException {
        try {
            Document doc = new Document();
            for (String field : FIELDS) {
            	String fieldValue = oldDoc.get(field);
            	if (fieldValue != null) {
            		doc.add(new StringField(field, fieldValue, org.apache.lucene.document.Field.Store.YES));
            	}
            	
            }
            /*
            for (Field field : Field.values()) {
                org.apache.lucene.document.Field.Store store = field.persist ? STORE : DONT_STORE;
                Object fieldValue = field.getValue(run);
                if (fieldValue == null && oldDoc != null) {
                    fieldValue = oldDoc.get(field.fieldName);
                }
                if (fieldValue != null) {
                    switch (FIELD_TYPE_MAP.get(field)) {
                    case LONG:
                        doc.add(new LongField(field.fieldName, ((Number) fieldValue).longValue(), store));
                        break;
                    case STRING:
                        doc.add(new StringField(field.fieldName, fieldValue.toString(), store));
                        break;
                    case TEXT:
                        doc.add(new TextField(field.fieldName, fieldValue.toString(), store));
                        break;
                    default:
                        throw new IllegalArgumentException("Don't know how to handle " + FIELD_TYPE_MAP.get(field));
                    }
                }
            }*/
            // I don't think we need?
            /*
            for (FreeTextSearchExtension extension : FreeTextSearchExtension.all()) {
                try {
                    Object fieldValue = extension.getTextResult(run);
                    if (fieldValue == null && oldDoc != null) {
                        fieldValue = oldDoc.get(extension.getKeyword());
                    }
                    if (fieldValue != null) {
                        doc.add(new TextField(extension.getKeyword(), extension.getTextResult(run), (extension
                                .isPersist()) ? STORE : DONT_STORE));
                    }
                } catch (Throwable t) {
                    //We don't want to crash the collection of log from other plugin extensions if we happen to add a plugin that crashes while collecting the logs.
                    System.out.println("CRASH: " + extension.getClass().getName() + ", " + extension.getKeyword());
                    t.printStackTrace();
                }
            }*/

            dbWriter.addDocument(doc);
        } finally {
            updateReader();
        }
    }

    //@Override
    // TODO: WHAT DOES THIS DO?? I think it removes the current build (originally called removeBuild)
    // Uses the current build's id as the value for ID field in the search term to find the document and delete it...
    public Document removeDoc(String className, String methodName) {//final Run<?, ?> run) {
        try {
        	Term classTerm = new Term(CLASS_NAME_FIELD, className);
        	Term methodTerm = new Term(METHOD_NAME_FIELD, className);
            //Term term = new Term(Field.ID.fieldName, run.getId());
            IndexSearcher searcher = new IndexSearcher(reader);
            TopDocs search = searcher.search(new TermQuery(classTerm), 1);
            Document doc = null;
            if (search.scoreDocs.length > 0) {
                doc = searcher.doc(search.scoreDocs[0].doc);
                dbWriter.deleteDocuments(classTerm, methodTerm);
                updateReader();
            }
            return doc;
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    // TODO: are we going to need this?
    //@Override
    /*public void cleanDeletedBuilds(Progress progress, Job<?, ?> job) throws Exception {
        try {
            int firstBuildNumber = job.getFirstBuild().getNumber();
            IndexSearcher searcher = new IndexSearcher(reader);

            Term term = new Term(Field.PROJECT_NAME.fieldName, job.getName().toLowerCase(LOCALE));
            Query q = new TermQuery(term).rewrite(reader);
            TopDocs topDocs = searcher.search(q, 9999999);

            for (int i = 0; i < topDocs.scoreDocs.length; i++) {
                Document doc = searcher.doc(topDocs.scoreDocs[i].doc);
                progress.setMax(reader.maxDoc());
                progress.setCurrent(i);
                Integer buildNumber = Integer.valueOf(doc.get(BUILD_NUMBER.fieldName));
                if (firstBuildNumber > buildNumber) {
                    String id = doc.get(ID.fieldName);
                    dbWriter.deleteDocuments(new Term(ID.fieldName, id));
                }
            }
            progress.setSuccessfullyCompleted();
            updateReader();
        } catch (Exception e) {
            progress.completedWithErrors(e);
            LOGGER.error("Failed to delete cleaned builds", e);
            throw e;
        } finally {
            progress.setFinished();
        }
    }*/

    //@Override
    public void deleteJob(String className) {//jobName) {
        try {
            //Term term = new Term(PROJECT_NAME.fieldName, jobName.toLowerCase(LOCALE));
        	Term term = new Term(CLASS_NAME_FIELD, className);
            dbWriter.deleteDocuments(term);
            updateReader();
        } catch (IOException e) {
            System.out.println("Could not delete job");
        }
    }

    // TODO: im not sure we need this, seems to returns just field definitions... (?)
    /*
    @Override
    public List<SearchFieldDefinition> getAllFieldDefinitions() throws IOException {
        Map<String, Boolean> fieldNames = new LinkedHashMap<String, Boolean>();
        for (Field field : Field.values()) {
            fieldNames.put(field.fieldName, field.persist);
        }
        for (FreeTextSearchExtension extension : FreeTextSearchExtension.all()) {
            fieldNames.put(extension.getKeyword(), extension.isPersist());
        }

        List<SearchFieldDefinition> definitions = new ArrayList<SearchFieldDefinition>();
        for (Map.Entry<String, Boolean> fieldEntry : fieldNames.entrySet()) {
            if (fieldEntry.getValue()) {
                // This is a persisted field (i.e. we can get values)
                IndexSearcher searcher = new IndexSearcher(reader);
                DistinctCollector collector = new LengthLimitedDistinctCollector(fieldEntry.getKey(), searcher, 50);
                searcher.search(new MatchAllDocsQuery(), collector);
                Set<String> distinctData = collector.getDistinctData();
                definitions.add(new SearchFieldDefinition(fieldEntry.getKey(), true, distinctData));
            } else {
                definitions.add(new SearchFieldDefinition(fieldEntry.getKey(), false, Collections.<String> emptyList()));
            }
        }
        return definitions;
    }*/

    // TODO: are we going to need this?
    /*@Override
    public void cleanDeletedJobs(Progress progress) throws Exception {
        try {
            Set<String> jobNames = new HashSet<String>();
            for (Job<?, ?> job : Jenkins.getInstance().getAllItems(Job.class)) {
                jobNames.add(job.getName());
            }
            progress.setMax(jobNames.size());
            IndexSearcher searcher = new IndexSearcher(reader);
            DistinctCollector distinctCollector = new DistinctCollector(PROJECT_NAME.fieldName, searcher);
            searcher.search(new MatchAllDocsQuery(), distinctCollector);
            int i = 0;
            for (String jobName : distinctCollector.getDistinctData()) {
                progress.setCurrent(i);
                if (!jobNames.contains(jobName)) {
                    deleteJob(jobName);
                }
                i++;
            }
            updateReader();
            progress.setSuccessfullyCompleted();
        } catch (Exception e) {
            progress.completedWithErrors(e);
            LOGGER.error("Failed to clean deleted jobs", e);
            throw e;
        } finally {
            progress.setFinished();
        }
    }*/

	
	
	
	
	
	
	
	
	
	
	
	
	
	
	

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