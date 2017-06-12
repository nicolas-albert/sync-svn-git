# sync-svn-git

[![Build Status](https://travis-ci.org/nicolas-albert/sync-svn-git.svg?branch=master)](https://travis-ci.org/nicolas-albert/sync-svn-git)

## What is __sync-svn-git__ ?

Application tool to keep up-to-date sources from SVN to many GIT repositories after a source migration.

To work, this project need a __config.properties__ in its working directory (root of the project if launched from Eclipse).

Sample of __config.properties__ content:

```
authorPath=c:/Users/myUser/git/authors.txt
svnRoot=http://svnhost/svn/svnroot
projects=lemon,cherry

project.lemon.svnProject=Fruits
project.lemon.svnPath=Basket/src/org/fruits/lemon
project.lemon.svnBranches=6.6.x,trunk
project.lemon.gitProject=c:/Users/myUser/git/fruits-lemon
project.lemon.gitPath=src/org/fruits/lemon
project.lemon.filter=lemon/(yellow|lime)/(?!cocktail|pie)

project.cherry.svnProject=Cherry
project.cherry.svnPath=Basket/src/org/fruits/cherry
project.cherry.svnBranches=trunk
project.cherry.gitProject=c:/Users/myUser/git/fruits-cherry
project.cherry.gitPath=src/org/fruits/cherry
project.cherry.filter=
```

It's a working project in my case and may need adaptations in your case.