package cs685.test.selection.ir;

import java.io.IOException;
import java.util.Iterator;
import java.util.List;

import hudson.FilePath;
import hudson.model.Item;
import jenkins.model.Jenkins;

public class IndexManager {
	private transient Indexer instance;
	private FilePath root;
	
	private synchronized Indexer getIndexer() throws IOException, InterruptedException {
		if (instance == null) {
			instance = new Indexer(root);
		}
		return instance;
	}
	
	public IndexManager(FilePath root) {
		this.root = root;
	}
	//TODO: may need to implement other methods
	public List<TestCase> getHits(String query, int n) throws IOException, InterruptedException {
        List<TestCase> hits = getIndexer().getHits(query, n);
        return hits;
        /* do we need this code??
        Jenkins jenkins = Jenkins.getInstance(); // TODO: is this just getting the information on a Jenkins build?
        Iterator<TestCase> iter = hits.iterator();
        while (iter.hasNext()) {
            TestCase searchItem = iter.next();
            // We may not need this jenkins stuff
            Item item = jenkins.getItem(searchItem.getId().toString()); // was .getProjectName()
            if (item == null) {
                iter.remove();
            }
        }
        return hits;*/
    }
	
	public void close() throws IOException, InterruptedException {
		getIndexer().close();
	}
}
