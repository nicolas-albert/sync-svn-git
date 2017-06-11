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
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.diff.DiffFormatter;
import org.eclipse.jgit.diff.RawTextComparator;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.storage.file.FileRepositoryBuilder;
import org.eclipse.jgit.util.io.DisabledOutputStream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.tmatesoft.svn.core.SVNDirEntry;
import org.tmatesoft.svn.core.SVNLogEntry;
import org.tmatesoft.svn.core.SVNLogEntryPath;
import org.tmatesoft.svn.core.SVNURL;
import org.tmatesoft.svn.core.internal.io.dav.DAVRepositoryFactory;
import org.tmatesoft.svn.core.io.SVNRepository;
import org.tmatesoft.svn.core.io.SVNRepositoryFactory;

public class Main {
	private static SVNRepository svn;
	private static Git git;
	private static Map<String, Entry<String, String>> authors = new HashMap<>();
	private static Properties config = new Properties();
	private static Logger log;
	private static int lookback = 20;
	
	public static void main(String[] args) throws Exception {
		System.setProperty(org.slf4j.impl.SimpleLogger.DEFAULT_LOG_LEVEL_KEY, "DEBUG");
		
		Logger logMain = log = LoggerFactory.getLogger("main");
		
		try (InputStream is = new FileInputStream("config.properties")) {
			config.load(is);
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
			log = LoggerFactory.getLogger(project);
			handleProject(
					config.getProperty("project." + project + ".svnPath"),
					config.getProperty("project." + project + ".oldPathPrefix"),
					config.getProperty("project." + project + ".gitURL"),
					config.getProperty("project." + project + ".gitPath"),
					config.getProperty("project." + project + ".filter")
			);
			log = logMain;
		}
		
		log.info("bye !");
	}
	
	@SuppressWarnings("unchecked")
	private static void handleProject(String svnPath, String oldPathPrefix, String gitURL, String gitPath, String filter) throws Exception {
		
		String svnURL = config.getProperty("svnRoot") + svnPath + "/" + oldPathPrefix + "/" + gitPath;
		File gitRoot = new File(gitURL).getParentFile();
		
		FileRepositoryBuilder builder = new FileRepositoryBuilder();
		Repository repository = builder.setGitDir(new File(gitURL)).readEnvironment().findGitDir().build();
		git = new Git(repository);
		
		log.debug("gitURL: " + gitURL);
		log.debug("svnURL: " + svnURL);
		
		svn = SVNRepositoryFactory.create(SVNURL.parseURIEncoded(svnURL));
		SVNDirEntry svninfo = svn.info("", -1);
		
		log.info("SVN revision: " + svninfo.getRevision());
		
		List<RevCommit> gitLogs = new ArrayList<>(lookback);
		git.log().addPath(gitPath).addPath(oldPathPrefix + "/" + gitPath).setMaxCount(50).call().forEach( r -> {
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
					if (checkLogEntry(gitLog, entry, gitPath, pFilter)) {
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
			handleLogEntry(svnEntries.previous(), gitPath, svnPath, gitRoot, pFilter);
		};
	}
	
	private static boolean checkLogEntry(RevCommit gitLog, SVNLogEntry entry, String gitPath, Pattern pFilter) throws Exception {
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
						path = path.substring(path.indexOf(gitPath));
						if (pFilter.matcher(path).find()) {
							gitSet.add(path);
						}
					});
				}
				Set<String> svnSet = new HashSet<>();
				entry.getChangedPaths().keySet().forEach(path -> {
					int i = path.indexOf(gitPath);
					if (i != -1) {
						path = path.substring(i);
						if (pFilter.matcher(path).find()) {
							svnSet.add(path);
						}
					}
				});
				
				boolean same = gitSet.equals(svnSet);
				log.debug("same content for " + entry.getRevision() + " ? " + same);
				return same;
			}
		}
		return false;
	}
	
	private static void handleLogEntry(SVNLogEntry logEntry, String gitPath, String svnPath, File gitRoot, Pattern pFilter) throws Exception {
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
			if (path.startsWith(svnPath) && path.contains(gitPath) && pFilter.matcher(path).find()) {
				log.info("handle: " + path);
				String subPath = path.substring(path.indexOf(gitPath));
				File dest = new File(gitRoot, subPath);
				switch (entry.getType()) {
				case SVNLogEntryPath.TYPE_DELETED:
					dest.delete();
					break;
				default:
					try (FileOutputStream fos = new FileOutputStream(dest)) {
						svn.getFile(entry.getPath(), logEntry.getRevision(), null, fos);
					};
				}
				git.add().addFilepattern(subPath).call();
				commitNeed = true;
			}
		}
		if (commitNeed) {
			git.commit()
				.setAuthor(author.getKey(), author.getValue())
				.setCommitter(author.getKey(), author.getValue())
				.setMessage(logEntry.getMessage())
				.call();
		}
	}
}
