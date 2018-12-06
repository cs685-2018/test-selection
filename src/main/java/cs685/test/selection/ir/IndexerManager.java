package cs685.test.selection.ir;

import java.util.Iterator;
import java.util.List;

import org.jenkinsci.plugins.lucene.search.FreeTextSearchItemImplementation;

import hudson.model.Item;
import jenkins.model.Jenkins;

public class IndexerManager {
	private transient Indexer instance;
	
	private synchronized Indexer getIndexer() {
		if (instance == null) {
			instance = new Indexer();
		}
		return instance;
	}
	
	/*public IndexManager() {
		
	}*/
	//TODO: may need to implement other methods
	public List<TestCase> getHits(String query, boolean includeHighlights) {
        List<TestCase> hits = getIndexer().getHits(query);
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
        return hits;
    }
}
