package cs685.test.generation;

import java.io.File;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Set;

import org.eclipse.jgit.lib.Repository;

import com.github.jenkins.lastchanges.impl.GitLastChanges;
import com.github.jenkins.lastchanges.model.CommitChanges;
import com.github.jenkins.lastchanges.model.LastChanges;

import hudson.FilePath;

public class TestGeneration {

    private HashMap<String, List<String>> map;
    private Repository gitRepository;
    private LastChanges lastChanges;
    
    public TestGeneration(HashMap<String, List<String>> map, FilePath workspaceDir) {
        this.map = map;
        this.gitRepository = GitLastChanges.repository(workspaceDir.getRemote() + "/.git");
    	this.lastChanges = GitLastChanges.getInstance().changesOf(gitRepository);
    	// We may need this? We may need to get more than one commit if we want to get X commits since last push
    	this.lastChanges.addCommit(new CommitChanges(lastChanges.getCurrentRevision(), lastChanges.getDiff()));
    }

    public Set<String> getClassNames() {
    	return this.map.keySet();
    }
    
    public List<String> getMethodNames(String className) {
    	if (this.map.containsKey(className)) {
    		return this.map.get(className);
    	} else {
    		return Collections.emptyList();
    	}
    }
	
	public List<CommitChanges> getChanges() {
		return lastChanges.getCommits();
	}
	public String getDifferences () {
		return lastChanges.getDiff();
	}
}
