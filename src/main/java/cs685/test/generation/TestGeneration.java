package cs685.test.generation;

import java.io.IOException;
import java.text.DateFormat;
import java.text.ParseException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Set;
import java.util.logging.Level;

import org.eclipse.jgit.errors.AmbiguousObjectException;
import org.eclipse.jgit.errors.IncorrectObjectTypeException;
import org.eclipse.jgit.errors.RevisionSyntaxException;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.Repository;
import org.tmatesoft.svn.core.wc.SVNRevision;

import com.github.jenkins.lastchanges.LastChangesBuildAction;
import com.github.jenkins.lastchanges.impl.GitLastChanges;
import com.github.jenkins.lastchanges.impl.SvnLastChanges;
import com.github.jenkins.lastchanges.model.CommitChanges;
import com.github.jenkins.lastchanges.model.CommitInfo;
import com.github.jenkins.lastchanges.model.LastChanges;

import hudson.FilePath;
import hudson.model.AbstractBuild;

public class TestGeneration {

    private HashMap<String, List<String>> map;
    private Repository gitRepository;
    private LastChanges lastChanges;
    
    public TestGeneration(HashMap<String, List<String>> map, FilePath workspaceDir, AbstractBuild build) throws RevisionSyntaxException, AmbiguousObjectException, IncorrectObjectTypeException, IOException {
        this.map = map;
        this.gitRepository = GitLastChanges.repository(workspaceDir.getRemote() + "/.git");
        
        // Checks if this Jenkins project had a previous successful build (with this plugin enabled)
        boolean hasSuccessfulBuild = build.getParent().getLastSuccessfulBuild() != null;
        if (hasSuccessfulBuild) {
        	// Calculate the revision from the last successful build
            LastChangesBuildAction action = build.getParent().getLastSuccessfulBuild().getAction(LastChangesBuildAction.class);
            if (action != null && action.getBuildChanges().getCurrentRevision() != null) {
                String targetRevision = action.getBuildChanges().getCurrentRevision().getCommitId();
                // Compares current repository revision with provided revision
                this.lastChanges = GitLastChanges.getInstance().changesOf(gitRepository, GitLastChanges.getInstance().resolveCurrentRevision(gitRepository), gitRepository.resolve(targetRevision));
                List<CommitInfo> commitInfoList = GitLastChanges.getInstance().getCommitsBetweenRevisions(
                		gitRepository,
                		gitRepository.resolve(lastChanges.getCurrentRevision().getCommitId()),
                        gitRepository.resolve(targetRevision)); 
                lastChanges.addCommits(this.commitChanges(commitInfoList, lastChanges.getPreviousRevision().getCommitId()));
            } else {
            	// LastChangesPublisher.java does not have an else here, can we get here?
            	System.out.println("Possible error to get here");
            	// Compare current repository revision with previous one just in case
            	this.lastChanges = GitLastChanges.getInstance().changesOf(gitRepository);
                this.lastChanges.addCommit(new CommitChanges(lastChanges.getCurrentRevision(), lastChanges.getDiff()));
            }
        } else {
        	// Compares current repository revision with previous one
            this.lastChanges = GitLastChanges.getInstance().changesOf(gitRepository);
            this.lastChanges.addCommit(new CommitChanges(lastChanges.getCurrentRevision(), lastChanges.getDiff()));
        }
    }
    
    // Private method taken from LastChangesPublisher.java and modified
    private List<CommitChanges> commitChanges(List<CommitInfo> commitInfoList, String oldestCommit) {
    	if (commitInfoList == null || commitInfoList.isEmpty()) {
            return null;
        }
        List<CommitChanges> commitChanges = new ArrayList<>();
        try {
        	// Sort CommitInfo list based on custom Comparator
            Collections.sort(commitInfoList, new Comparator<CommitInfo>() {
                @Override
                public int compare(CommitInfo c1, CommitInfo c2) {
                    try {
                        DateFormat format = DateFormat.getDateTimeInstance(DateFormat.DEFAULT, DateFormat.DEFAULT);
                        return format.parse(c1.getCommitDate()).compareTo(format.parse(c2.getCommitDate()));
                    } catch (ParseException e) {
                        System.out.println(String.format("Could not parse commit dates %s and %s ", c1.getCommitDate(), c2.getCommitDate()));
                        return 0;
                    }
                }
            });

            for (int i = commitInfoList.size() - 1; i >= 0; i--) {
                LastChanges lastChanges = null;
                ObjectId previousCommit = gitRepository.resolve(commitInfoList.get(i).getCommitId() + "^1");
                lastChanges = GitLastChanges.getInstance().
                        changesOf(gitRepository, gitRepository.resolve(commitInfoList.get(i).getCommitId()), previousCommit);
                
                String diff = lastChanges != null ? lastChanges.getDiff() : "";
                commitChanges.add(new CommitChanges(commitInfoList.get(i), diff));
            }

        } catch (Exception e) {
            System.out.println("Could not get commit changes.");
            e.printStackTrace();
        }

        return commitChanges;
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
