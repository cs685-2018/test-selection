package cs685.test.selection;

import java.io.IOException;
import java.text.DateFormat;
import java.text.ParseException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

import org.eclipse.jgit.errors.AmbiguousObjectException;
import org.eclipse.jgit.errors.IncorrectObjectTypeException;
import org.eclipse.jgit.errors.RevisionSyntaxException;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.Repository;

import com.github.jenkins.lastchanges.LastChangesBuildAction;
import com.github.jenkins.lastchanges.impl.GitLastChanges;
import com.github.jenkins.lastchanges.model.CommitChanges;
import com.github.jenkins.lastchanges.model.CommitInfo;
import com.github.jenkins.lastchanges.model.LastChanges;

import hudson.FilePath;
import hudson.model.AbstractBuild;

/**
 * 
 * @author Ryan
 *
 */
public class TestSelection {

    private Repository gitRepository;
    private LastChanges lastChanges;
    
    /**
     * Object to get the last changes between two commits given the Git repo in the workspace directory
     * @param map
     * @param workspaceDir
     * @param build
     * @throws RevisionSyntaxException
     * @throws AmbiguousObjectException
     * @throws IncorrectObjectTypeException
     * @throws IOException
     */
    public TestSelection(FilePath workspaceDir, AbstractBuild build) throws RevisionSyntaxException, AmbiguousObjectException, IncorrectObjectTypeException, IOException {
        this.gitRepository = GitLastChanges.repository(workspaceDir.getRemote() + "/.git");
        
        System.out.println("git repo: " + workspaceDir.getRemote());
        
        // Checks if this Jenkins project had a previous successful build (with this plugin enabled)
        boolean hasSuccessfulBuild = build.getParent().getLastSuccessfulBuild() != null;
        if (hasSuccessfulBuild) {
        	System.out.println("We've had a previously successful build!");
        	// Calculate the revision from the last successful build
            LastChangesBuildAction action = build.getParent().getLastSuccessfulBuild().getAction(LastChangesBuildAction.class);
            if (action != null && action.getBuildChanges().getCurrentRevision() != null) {
            	System.out.println("Action and CurrentRevision are not null!");
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
            	System.out.println("Possible error to get here, action or currentrevision are null");
            	// Compare current repository revision with previous one just in case
            	this.lastChanges = GitLastChanges.getInstance().changesOf(gitRepository);
                this.lastChanges.addCommit(new CommitChanges(lastChanges.getCurrentRevision(), lastChanges.getDiff()));
            }
        } else {
        	System.out.println("We've had no previously successful build!");
        	// Compares current repository revision with previous one
            this.lastChanges = GitLastChanges.getInstance().changesOf(gitRepository);
            this.lastChanges.addCommit(new CommitChanges(lastChanges.getCurrentRevision(), lastChanges.getDiff()));
        }
    }
    
    /**
     * Private method taken from LastChangesPublisher.java and modified
     * 
     * @param commitInfoList
     * @param oldestCommit
     * @return
     */
    private List<CommitChanges> commitChanges(List<CommitInfo> commitInfoList, String oldestCommit) {
    	if (commitInfoList == null || commitInfoList.isEmpty()) {
    		if (commitInfoList == null) {
    			System.out.println("Our commitInfoList is null!");
    		} 
    		else if (commitInfoList.isEmpty()) {
    			System.out.println("Our commitInfoList is empty!");
    		}
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

            System.out.println("Iterate over commitInfoList:");
            for (int i = commitInfoList.size() - 1; i >= 0; i--) {
                LastChanges lastChanges = null;
                ObjectId previousCommit = gitRepository.resolve(commitInfoList.get(i).getCommitId() + "^1");
                lastChanges = GitLastChanges.getInstance().
                        changesOf(gitRepository, gitRepository.resolve(commitInfoList.get(i).getCommitId()), previousCommit);
                
                String diff = lastChanges != null ? lastChanges.getDiff() : "";
                System.out.println("diff[" + Integer.toString(i) + "] = " + diff);
                commitChanges.add(new CommitChanges(commitInfoList.get(i), diff));
            }

        } catch (Exception e) {
            System.out.println("Could not get commit changes.");
            e.printStackTrace();
        }

        return commitChanges;
    }

    /**
     * Returns the List of CommitChanges objects
     * @return
     */
	public List<CommitChanges> getChanges() {
		return lastChanges.getCommits();
	}
	
	/**
	 * Returns the DIFF file as a String between the two commits
	 * @return
	 */
	public String getDifferences () {
		System.out.println("Getting differences...");
		System.out.println("getDiff: " + lastChanges.getDiff());
		return lastChanges.getDiff();
	}
}
