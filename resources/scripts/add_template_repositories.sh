#!/bin/bash

cd /opt/git
for repo in `find . -type d -mindepth 1 -maxdepth 3 -name "*.git"`; do
        if ! [[ -d "$GERRIT_SITE/git/$repo" ]]; then
            echo "Adding template repository $GERRIT_SITE/git/$repo"
            su-exec ${GERRIT_USER} mkdir -p "$GERRIT_SITE/git/$repo"
            su-exec ${GERRIT_USER} cp -r "$repo" "$GERRIT_SITE/git/$repo/../"
            chown -R $GERRIT_USER "$GERRIT_SITE/git/"
        else
            su-exec ${GERRIT_USER} git config --global user.email "you@example.com";
            su-exec ${GERRIT_USER} git config --global user.name "Admin";
            echo "working with ${repo}";
            git clone file://$GERRIT_SITE/git/$repo dst_repo ;
            cd "/opt/git/dst_repo";
            git remote add source /opt/git/$repo
            git fetch source
            chown -R ${GERRIT_USER} /opt/git/dst_repo;
            for i in $(git branch -r | grep -Ev '^[ \t]*origin.*' | sed "s#^[ \t]*source/##" | grep -Ev '^master$') ; do
              git checkout -B $i source/$i
              git push origin refs/heads/$i:$i --force || echo "No push to repo";
            done
            cd /opt/git
            rm -rf /opt/git/dst_repo
        fi
done

# check if GERRIT_SITE is not empty and enforce reindex
if [ "$(ls -A $GERRIT_SITE/git/All-* &2>&1)" ]; then
  cd $GERRIT_SITE
  echo "Reindexing Gerrit repositories"
  su-exec ${GERRIT_USER} java -jar $GERRIT_SITE/bin/gerrit.war reindex
fi

## TODO: update codebases with new gerrit URL
mkdir -p /tmp/libraries/registry-regulations-publication-pipeline
cd /tmp/libraries/registry-regulations-publication-pipeline
git clone /var/gerrit/review_site/git/libraries/registry-regulations-publication-pipeline.git
cd registry-regulations-publication-pipeline
git config --global user.email "you@example.com"
git config --global user.name "Admin"
for codebase in $(grep -r -l 'cicd2' ./); do
  sed -i "s#https://gerrit-mdtu-ddm-edp-cicd.apps.cicd2.mdtu-ddm.projects.epam.com#${WEBURL}#g" ${codebase}
  git commit -a -m 'fixed codebase gerrit URL' || echo "Nothing to commit";
  git push || echo "No push to repo";
done
## end of TODO