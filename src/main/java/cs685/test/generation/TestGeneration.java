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
        /*
        URI remoteURL = null;
		try {
			remoteURL = new URI("https:///github.com/zembrodt/cs685-hw2");
		} catch (URISyntaxException e) {
			System.out.println("Could not find github url");
			e.printStackTrace();
		}
    	File remoteFile = new File(remoteURL); // TODO: get git repo url from jenkins project
    	FilePath workspaceTargetDir = new FilePath(remoteFile);
    	*/
    	//this.gitRepository = GitLastChanges.repository(workspaceTargetDir.getRemote() + "/.git")
        System.out.println("***TestGeneration().workspaceDir (FilePath): " + workspaceDir);
        System.out.println("***TestGeneration().workspaceDir.isRemote() (boolean): " + workspaceDir.isRemote());
        System.out.println("***TestGeneration().workspaceDir.getRemote() (String): " + workspaceDir.getRemote());
        File remoteGitDir = new File(workspaceDir.getRemote() + "/.git");
        System.out.println("***TestGeneration().remoteGitDir (File): " + remoteGitDir);
        this.gitRepository = GitLastChanges.repository(workspaceDir.getRemote() + "/.git");
    	this.lastChanges = GitLastChanges.getInstance().changesOf(gitRepository);
    	// We may need this?
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
