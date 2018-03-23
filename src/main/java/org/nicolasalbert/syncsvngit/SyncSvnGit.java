package org.nicolasalbert.syncsvngit;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.AbstractMap.SimpleImmutableEntry;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.ListIterator;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Properties;
import java.util.Set;
import java.util.TimeZone;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.diff.DiffFormatter;
import org.eclipse.jgit.diff.RawTextComparator;
import org.eclipse.jgit.lib.PersonIdent;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.storage.file.FileRepositoryBuilder;
import org.eclipse.jgit.util.io.DisabledOutputStream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.tmatesoft.svn.core.SVNDirEntry;
import org.tmatesoft.svn.core.SVNLogEntry;
import org.tmatesoft.svn.core.SVNLogEntryPath;
import org.tmatesoft.svn.core.SVNNodeKind;
import org.tmatesoft.svn.core.SVNURL;
import org.tmatesoft.svn.core.internal.io.dav.DAVRepositoryFactory;
import org.tmatesoft.svn.core.io.SVNRepository;
import org.tmatesoft.svn.core.io.SVNRepositoryFactory;

public class SyncSvnGit {
	private static SVNRepository svn;
	private static Git git;
	private static Map<String, Entry<String, String>> authors = new HashMap<>();
	private static Properties config = new Properties();
	private static Logger log;
	private static int lookback = 20;
	
	public static void main(String[] args) throws Exception {		
		Logger logMain = log = LoggerFactory.getLogger("main");
		
		{
			File configFile = new File("config.properties"); 
			if (configFile.exists()) {
				try (InputStream is = new FileInputStream("config.properties")) {
					config.load(is);
				}
			} else {
				log.error("cannot load config from because it doesn't exist: " + configFile.getAbsolutePath());
				System.exit(1);
			}
		}
		
		log.debug("config loaded");
		
		try (BufferedReader br = new BufferedReader(new InputStreamReader(new FileInputStream(config.getProperty("authorPath")), "UTF-8"))) {
			Pattern pAuthor = Pattern.compile("(.*) = (.*) <(.*)>");
			String line;
			while ((line = br.readLine()) != null) {
				Matcher author = pAuthor.matcher(line);
				if (author.find()) {
					authors.put(author.group(1), new SimpleImmutableEntry<>(author.group(2), author.group(3)));
					log.trace("authors add " + author.group(0));
				}
			}
		}
		
		DAVRepositoryFactory.setup();
		
		for (String project : config.getProperty("projects").split("\\s*,\\s*")) {
			String[] gitBranches = config.getProperty("project." + project + ".gitBranches").split(",");
			int i = 0;
			for (String svnBranch: config.getProperty("project." + project + ".svnBranches").split(",")) {
				log = LoggerFactory.getLogger(project + "[" + svnBranch.replace('.', '-') + "]");
				handleProject(
						config.getProperty("project." + project + ".svnProject"),
						config.getProperty("project." + project + ".svnPath"),
						svnBranch,
						config.getProperty("project." + project + ".gitProject"),
						config.getProperty("project." + project + ".gitPath"),
						gitBranches[i++],
						config.getProperty("project." + project + ".filter")
				);
			}
			log = logMain;
		}
		
		log.info("bye !");
	}
	
	@SuppressWarnings("unchecked")
	private static void handleProject(String svnProject, String svnPath, String svnBranch, String gitProject, String gitPath, String gitBranch, String filter) throws Exception {
		svnBranch = svnBranch.equals("trunk") ? svnBranch : ("branches/" + svnBranch);
		String svnURL = config.getProperty("svnRoot") + "/" + svnProject + "/" + svnBranch + "/" + svnPath;
		
		File gitRoot = new File(gitProject);
		
		git = new Git(new FileRepositoryBuilder().setGitDir(new File(gitRoot, ".git")).readEnvironment().findGitDir().build());
//		git.clean().setForce(true).call();
		log.info("doing git checkout ...");
		git.checkout().setName(gitBranch).call();
		log.info("git checkout done");
		
		log.debug("gitProject: " + gitProject);
		log.debug("svnURL: " + svnURL);
		
		svn = SVNRepositoryFactory.create(SVNURL.parseURIEncoded(svnURL));
		SVNDirEntry svninfo = svn.info("", -1);
		
		log.info("SVN revision: " + svninfo.getRevision());
		
		List<RevCommit> gitLogs = new ArrayList<>(lookback);
		git.log().addPath(gitPath).addPath(svnPath).setMaxCount(50).call().forEach( r -> {
			gitLogs.add(r);
		});
		ListIterator<SVNLogEntry> svnEntries = new LinkedList<SVNLogEntry>().listIterator();
		Pattern pFilter = Pattern.compile(filter);
		
		SVNLogEntry logEntry = null;
		long curRev = svninfo.getRevision();
		while (logEntry == null) {
			log.debug("curRev : " + curRev);
			long endRevision = curRev;
			long startRevision = curRev - 10;
			List<SVNLogEntry> logs = new ArrayList<>((Collection<SVNLogEntry>) svn.log(new String[] {""}, null, startRevision, endRevision, true, true));
			Collections.reverse(logs);
			for (SVNLogEntry entry : logs) {
				svnEntries.add(entry);
				log.debug("rev : " + entry.getRevision());
				for (RevCommit gitLog : gitLogs) {
					if (checkLogEntry(gitLog, entry, svnPath, gitPath, pFilter)) {
						logEntry = entry;
						svnEntries.previous();
						break;
					}
				}
				if (logEntry != null) {
					break;
				}
			}
			curRev = startRevision - 1;
		}
		
		log.info("found: " + logEntry.getRevision());
		
		while (svnEntries.hasPrevious()) {
			handleLogEntry(svnEntries.previous(), svnPath, "/" + svnProject + "/" + svnBranch, gitRoot, gitPath, pFilter);
		};
	}
	
	private static boolean checkLogEntry(RevCommit gitLog, SVNLogEntry entry, String svnPath, String gitPath, Pattern pFilter) throws Exception {
		if (gitLog.getFullMessage().contains(entry.getMessage())) {
			log.trace("same message for " + entry.getRevision());
			if (gitLog.getAuthorIdent().getName().equals(authors.get(entry.getAuthor()).getKey())) {
				log.trace("same author for " + entry.getRevision());
				Set<String> gitSet = new HashSet<>();
				try (DiffFormatter df = new DiffFormatter(DisabledOutputStream.INSTANCE)) {
					df.setRepository(git.getRepository());
					df.setDiffComparator(RawTextComparator.DEFAULT);
					df.setDetectRenames(true);

					df.scan(gitLog.getParent(0).getTree(), gitLog.getTree()).forEach(d -> {
						String path = d.getNewPath();
						String prePath = path.startsWith(gitPath) ? gitPath : path.startsWith(svnPath) ? svnPath : null;
						if (prePath != null && pFilter.matcher(path).find()) {
							gitSet.add(path.substring(prePath.length()));
						}
					});
				}
				Set<String> svnSet = new HashSet<>();
				entry.getChangedPaths().keySet().forEach(path -> {
					int i = path.indexOf(svnPath);
					if (i != -1 && pFilter.matcher(path).find()) {
						svnSet.add(path.substring(i + svnPath.length()));
					}
				});
				
				boolean same = gitSet.equals(svnSet);
				log.debug("same content for " + entry.getRevision() + " ? " + same);
				return same;
			}
		}
		return false;
	}
	
	private static void handleLogEntry(SVNLogEntry logEntry, String svnPath, String svnPrefix, File gitRoot, String gitPath, Pattern pFilter) throws Exception {
		log.info("previous: " + logEntry.getRevision());
		Entry<String, String> author = authors.get(logEntry.getAuthor());
		if (author == null) {
			log.error("Bad author: " + author);
			System.exit(1);
		}
		boolean commitNeed = false;
		for (SVNLogEntryPath entry : logEntry.getChangedPaths().values()) {
			String path = entry.getPath();
			log.debug(entry.getType() + " " + path);
			if (path.startsWith(svnPrefix) && path.contains(svnPath) && pFilter.matcher(path).find()) {
				log.info("handle: " + entry.getType() + " " + path);
				path = path.substring(svnPrefix.length() + 1);
				String prePath = new File(gitRoot, gitPath).exists() ? gitPath : new File(gitRoot, svnPath).exists() ? svnPath : null;
				String subPath = prePath + path.substring(path.indexOf(svnPath) + svnPath.length());
				File dest = new File(gitRoot, subPath);
				switch (entry.getType()) {
				case SVNLogEntryPath.TYPE_DELETED:
					git.rm().addFilepattern(subPath).call();
					break;
				default:
					SVNNodeKind kind = entry.getKind(); 
					if (kind.equals(SVNNodeKind.DIR)) {
						dest.mkdirs();
					} else if (kind.equals(SVNNodeKind.FILE)) {
						dest.getParentFile().mkdirs();
						try (FileOutputStream fos = new FileOutputStream(dest)) {
							svn.getFile(entry.getPath(), logEntry.getRevision(), null, fos);
						};
					} else {
						log.warn("cannot handle node of kind: " + kind + " for: " + dest);
					}
					git.add().addFilepattern(subPath).call();
				}
				commitNeed = true;
			}
		}
		if (commitNeed) {
			PersonIdent pi = new PersonIdent(author.getKey(), author.getValue(), logEntry.getDate(), TimeZone.getDefault());
			git.commit()
				.setAuthor(pi)
				.setCommitter(pi)
				.setMessage(logEntry.getMessage())
				.call();
		}
	}
}
