package cs685.test.selection.ir;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.Stack;

import org.apache.lucene.queryparser.classic.ParseException;

import com.github.javaparser.JavaParser;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;

import hudson.FilePath;
import io.reflectoring.diffparser.api.model.Diff;
import io.reflectoring.diffparser.api.model.Hunk;
import io.reflectoring.diffparser.api.model.Line;
import io.reflectoring.diffparser.api.model.Range;

/**
 * Used to create queries based on Diffs and access an Indexer
 * @author Ryan
 *
 */
public class InformationRetriever {
	// Static member variables
	private static final String STOPWORDS_FILENAME = "/stopwords.txt";
	private static final String KEYWORDS_FILENAME = "/keywords.txt";
	
	private static Set<String> stopwords;
	private static Set<String> keywords;
	
	// Static methods
	/**
	 * Removes the stopwords from the string as defined in its file
	 * @param s
	 * @return
	 */
	public static String removeStopwords(String s) {
		StringBuilder modifiedStr = new StringBuilder();
		for (String part : s.split("\\s+")) {
			if (!stopwords.contains(part) && part.length() > 1) {
				modifiedStr.append(part);
				modifiedStr.append(" ");
			}
		}
		return modifiedStr.toString();
	}
	
	/**
	 * Removes the stopwrods and Java keywords from the string as defined within their respective files
	 * @param s
	 * @return
	 */
	public static String removeStopwordsAndKeywords(String s) {
		StringBuilder modifiedStr = new StringBuilder();
		for (String part : s.split("\\s+")) {
			// Check if len > 1 to avoid variables named "i", etc.
			if (!stopwords.contains(part) && !keywords.contains(part) && part.length() > 1) {
				modifiedStr.append(part);
				modifiedStr.append(" ");
			}
		}
		return modifiedStr.toString();
	}

	// Member variables
	private List<Query> queries;
	private IndexManager indexManager;
	
	/**
	 * Creates the queries based on the diffs and files within the project (root)<br>
	 * Creates an indexer based on files within the project
	 * 
	 * @param root
	 * @param diffs
	 * @throws IOException
	 * @throws InterruptedException 
	 */
	public InformationRetriever(FilePath root, List<Diff> diffs, String projectName) throws IOException, InterruptedException {
		// Load stopwords and keywords
		stopwords = new HashSet<String>();
		keywords = new HashSet<String>();
		try (BufferedReader br = new BufferedReader(new InputStreamReader(
				InformationRetriever.class.getResourceAsStream(STOPWORDS_FILENAME)))) {
			for (String line; (line = br.readLine()) != null;) {
				stopwords.add(line.replaceAll("\\'", "")); // remove single quotes
			}
		}
		try (BufferedReader br = new BufferedReader(new InputStreamReader(
				InformationRetriever.class.getResourceAsStream(KEYWORDS_FILENAME)))) {
			for (String line; (line = br.readLine()) != null;) {
				keywords.add(line);
			}
		}
		
		System.out.println("Stopwords size: " + Integer.toString(stopwords.size()));
		System.out.println("Keywords size: " + Integer.toString(keywords.size()));
		
		// A list of the files that may need to be updated (if an index already exists)
		Set<String> filesToUpdate = new HashSet<String>();
		
		// Get filename for each diff
		Map<String, Diff> filenameToDiff = new HashMap<String, Diff>();
		System.out.println("Creating filenameToDiff map");
		for (Diff diff : diffs) {
			String toFile = diff.getToFileName();
			System.out.println("Processing diff for " + toFile);
			if (toFile.endsWith(".java")) {
				String filename = toFile.replaceAll("b\\/", "");
				filenameToDiff.put(filename, diff);
				filesToUpdate.add(filename);
				System.out.println("Inserted diff for file [" + toFile.replaceAll("b\\/", "") + "] into map");
			}
		}
		
		System.out.println("filenameToDiff map has " + Integer.toString(filenameToDiff.size()) + " files!");
		
		// Get FilePath object for each diff
		Map<Diff, FilePath> diffToFilePath = new HashMap<Diff, FilePath>();
		System.out.println("Creating diffToFilePath map!");
		Stack<FilePath> filesToProcess = new Stack<>();
		filesToProcess.push(root);
		while (!filesToProcess.isEmpty()) {
			FilePath currPath = filesToProcess.pop();
			if (currPath.isDirectory()) {
				filesToProcess.addAll(currPath.list());
			} else if (currPath.getName().endsWith(".java")) {
				// Check if file is in our filenameToDiff map
				String currFile = currPath.getRemote();
				System.out.println("Found Java file: [" + currPath.getName() + "]");
				System.out.println("getRemote(): [" + currPath.getRemote() + "]");
				String currFilePathSplit[] = currPath.getRemote().split(projectName);
				String currFilePath = null;
				if (currFilePathSplit.length != 2) {
					System.out.println("ERROR: File not in project's directory?");
				} else {
					currFilePath = currFilePathSplit[1].substring(1); // remove the leading forward slash
				}
				System.out.println("modified: [" + currFilePath + "]");
				if (filenameToDiff.containsKey(currFilePath)) {
					// Add the FilePath to diffToFilePath
					System.out.println("We found a matching FilePath! [" + currFile + "]");
					diffToFilePath.put(filenameToDiff.get(currFilePath), currPath);
				}
			}
		}
		
		System.out.println("diffToFilePath map has " + Integer.toString(diffToFilePath.size()) + " FilePaths!");
		
		queries = new ArrayList<Query>();
		// Build the queries from each diff
		// TODO: add logger that works with Jenkins?
		System.out.println("Processing diffs:");
		for (Diff diff : diffToFilePath.keySet()) {
			System.out.println("\t" + diff);
		}
		for (Diff diff : diffToFilePath.keySet()) {
			Map<String, List<Range>> classToRange = new HashMap<String, List<Range>>();
			Map<String, List<Range>> methodToRange = new HashMap<String, List<Range>>();
			CompilationUnit cu = JavaParser.parse(diffToFilePath.get(diff).read());

			// Find line number ranges of all declared classes
			System.out.println("Classes:");
			List<ClassOrInterfaceDeclaration> classDelcarations = cu.findAll(ClassOrInterfaceDeclaration.class);
			for (ClassOrInterfaceDeclaration classDeclaration : classDelcarations) {
				System.out.println("\t" + classDeclaration.getName().asString());
				Optional<com.github.javaparser.Range> lineNumberRange = classDeclaration.getRange();
				if (lineNumberRange.isPresent()) {
					System.out.println("\t\tLine number range: " + lineNumberRange.get().begin + " to "
							+ lineNumberRange.get().end);
					String className = classDeclaration.getName().asString();
					Range range = new Range(lineNumberRange.get().begin.line,
							lineNumberRange.get().end.line - lineNumberRange.get().begin.line);
					if (!classToRange.containsKey(className)) {
						classToRange.put(className, new ArrayList<Range>());
					}
					classToRange.get(className).add(range);
				} else {
					System.out.println("\t\tLine number range does not exist");
				}
			}

			// Find line number ranges of all declared methods
			System.out.println("\nMethods:");
			List<MethodDeclaration> methodDeclarations = cu.findAll(MethodDeclaration.class);
			for (MethodDeclaration method : methodDeclarations) {
				System.out.println("\t" + method.getName().asString());
				// Line number
				Optional<com.github.javaparser.Range> lineNumberRange = method.getRange();
				if (lineNumberRange.isPresent()) {
					System.out.println("\t\tLine number range: " + lineNumberRange.get().begin + " to "
							+ lineNumberRange.get().end);
					String methodName = method.getName().asString();
					Range range = new Range(lineNumberRange.get().begin.line,
							lineNumberRange.get().end.line - lineNumberRange.get().begin.line);
					if (!methodToRange.containsKey(methodName)) {
						methodToRange.put(methodName, new ArrayList<Range>());
					}
					methodToRange.get(methodName).add(range);
				} else {
					System.out.println("\t\tLine number range does not exist");
				}
			}

			// Get all the different hunks in the diff for the current file
			for (Hunk hunk : diff.getHunks()) {
				// Determine the line number range of the hunk
				io.reflectoring.diffparser.api.model.Range lineRange = hunk.getToFileRange();
				int startLine = lineRange.getLineStart();
				int endLine = startLine + lineRange.getLineCount();
				System.out.println("Hunk start=" + startLine + ", end=" + endLine);
				// Find all classes and methods contained in these lines
				Set<String> classes = new HashSet<String>();
				Set<String> methods = new HashSet<String>();
				Set<String> unparsedClasses = new HashSet<String>();
				Set<String> unparsedMethods = new HashSet<String>();
				for (String className : classToRange.keySet()) {
					for (Range r : classToRange.get(className)) {
						int begin = r.getLineStart();
						int end = begin + r.getLineCount();
						System.out.println("Method=" + className + " (" + begin + ", " + end + ")");
						// Find if our hunk block intersects with this class's block
						if ((startLine >= begin && startLine <= end) || (endLine >= begin && endLine <= end)) {
							unparsedClasses.add(className);
							String parsedClassName = Indexer.parseCamelCase(className).toLowerCase();
							classes.add(removeStopwords(parsedClassName));
						}
					}
				}
				for (String methodName : methodToRange.keySet()) {
					for (Range r : methodToRange.get(methodName)) {
						int begin = r.getLineStart();
						int end = begin + r.getLineCount();
						System.out.println("Method=" + methodName + " (" + begin + ", " + end + ")");
						// Find if our hunk block intersects with this class's block
						if ((startLine >= begin && startLine <= end) || (endLine >= begin && endLine <= end)) {
							unparsedMethods.add(methodName);
							String parsedMethodName = Indexer.parseCamelCase(methodName).toLowerCase();
							methods.add(removeStopwords(parsedMethodName));
						}
					}
				}
				System.out.println("Hunk intersects with classes: " + classes.toString());
				System.out.println("Hunk intersects with methods: " + methods.toString());
				// Create a query based on the hunk (hunk's content, methods hunk is in, classes
				// hunk is in)
				StringBuilder query = new StringBuilder();
				query.append(String.join(" ", classes));
				query.append(String.join(" ", methods));
				List<String> toLines = new ArrayList<String>();
				List<String> fromLines = new ArrayList<String>();
				// Find all TO and FROM lines in the hunk
				for (Line line : hunk.getLines()) {
					if (line.getLineType() == Line.LineType.TO || line.getLineType() == Line.LineType.FROM) {
						if (line.getLineType() == Line.LineType.TO) {
							toLines.add(line.getContent());
						} else {
							fromLines.add(line.getContent());
						}
					}
				}
				// Add all TO lines to the query
				for (String line : toLines) {
					// Remove punctuation
					line = line.replaceAll("[^A-Za-z\\s]", " ");
					line = Indexer.parseCamelCase(line.trim()).toLowerCase();
					// Parse out stopwords and Java keywords
					query.append(removeStopwordsAndKeywords(line));
				}
				queries.add(new Query(String.join(", ", unparsedClasses), String.join(", ", unparsedMethods),
						query.toString()));
			}
		}
		
		System.out.println("We created " + Integer.toString(queries.size()) + " queries!");
		
		// TODO: change to logging so we can check our queries
		System.out.println("Queries:");
		for (Query query : queries) {
			System.out.println("\t" + query);
		}
		
		// Create/update the indexer of all test files within the project
		indexManager = new IndexManager(root, projectName, filesToUpdate);
	}
	
	/**
	 * Get the top-n test documents based on the queries generated
	 * 
	 * @param n
	 * @return
	 * @throws IOException 
	 * @throws ParseException 
	 * @throws InterruptedException 
	 */
	public Set<String> getTestDocuments(int n) throws ParseException, IOException, InterruptedException { // TODO: possible update this to return a Set of a custom class
		Set<String> results = new HashSet<String>();
		// Loop over all our queries
		for (Query query : queries) {
			// Find top n documents
			List<TestCase> topDocs = indexManager.getHits(query.getQuery(), n);
			System.out.println("Query: [" + query.getQuery() + "]");
			System.out.println("\tCovers: " + query.getCoverages());
			for (TestCase testCase : topDocs) {
				results.add(testCase.getClassName() + "." + testCase.getMethodName());
			}
		}
		return results;
	}
	
	public void close() throws IOException, InterruptedException {
		indexManager.close();
	}
}
