package cs685.test.selection.ir;

import java.io.IOException;
import java.util.List;
import java.util.Set;

import hudson.FilePath;

/**
 * Manages access to an Indexer instance
 * @author Ryan
 *
 */
public class IndexManager {
	private transient Indexer instance;
	private FilePath root;
	private String projectName;
	private Set<String> filesToUpdate;
	
	/**
	 * Returns the current indexer or a new one
	 * @return
	 * @throws IOException
	 * @throws InterruptedException
	 */
	private synchronized Indexer getIndexer() throws IOException, InterruptedException {
		if (instance == null) {
			instance = new Indexer(root, projectName, filesToUpdate);
		}
		return instance;
	}
	
	/**
	 * 
	 * @param root
	 * @param projectName
	 * @param filesToUpdate
	 */
	public IndexManager(FilePath root, String projectName, Set<String> filesToUpdate) {
		this.root = root;
		this.projectName = projectName;
		this.filesToUpdate = filesToUpdate;
	}
	
	/**
	 * Returns the top n documents as TestCase objects given the query
	 * @param query
	 * @param n
	 * @return
	 * @throws IOException
	 * @throws InterruptedException
	 */
	public List<TestCase> getHits(String query, int n) throws IOException, InterruptedException {
        List<TestCase> hits = getIndexer().getHits(query, n);
        return hits;
    }
	
	public void close() throws IOException, InterruptedException {
		getIndexer().close();
	}
}
