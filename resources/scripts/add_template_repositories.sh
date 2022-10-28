#!/bin/bash

cd /opt/git
for repo in `find . -type d \( -name "*.git" ! -name "*cicd*" \)`; do
        if ! [[ -d "$GERRIT_SITE/git/$repo" ]]; then
#            rm -rvf $GERRIT_SITE/git/$repo
            echo "Adding template repository $GERRIT_SITE/git/$repo"
            su-exec ${GERRIT_USER} mkdir -p "$GERRIT_SITE/git/$repo"
            su-exec ${GERRIT_USER} cp -r "$repo" "$GERRIT_SITE/git/$repo/../"
            chown -R $GERRIT_USER "$GERRIT_SITE/git/"
        else
            su-exec ${GERRIT_USER} git config --global user.email "you@example.com";
            su-exec ${GERRIT_USER} git config --global user.name "Admin";
            echo "working with ${repo}";
            git clone file:///opt/git/$repo source_repo;
            git clone file://$GERRIT_SITE/git/$repo dst_repo ;
            cd "/opt/git/source_repo";
            chown -R ${GERRIT_USER} /opt/git/source_repo;
            chown -R ${GERRIT_USER} /opt/git/dst_repo;
            for i in $(git branch -r | sed "s#^[ \t]*origin/##" | grep -Ev '^master$' | grep -Ev '^HEAD') ; do
              if [[ `cd /opt/git/dst_repo && git branch -r | grep -E "^[ \t]*origin/$i"` ]]; then
                  echo "Branch $i exists, skipping update"
              else
                git checkout $i;
                cd "/opt/git/dst_repo";
                su-exec ${GERRIT_USER} git checkout -f -B $i ;
                cp ./deploy-templates/values.yaml /tmp/values-backup.yaml || echo "No values.yaml to backup";
                rm -rf ./*;
                rm -rf /opt/git/source_repo/.git; rm -f /opt/git/source_repo/.gitignore /opt/git/source_repo/.helmignore;
                scp -rp /opt/git/source_repo/* ./ || echo "Nothing to copy";
                mv /tmp/values-backup.yaml ./deploy-templates/values.yaml || echo "No values.yaml to restore";
                chown -R ${GERRIT_USER} ./ && chown -R ${GERRIT_USER} ./.git;
                su-exec ${GERRIT_USER} git add --all || echo "Nothing to add";
                su-exec ${GERRIT_USER} git commit -am "Add new branch $i " || echo "Nothing to commit";
                su-exec ${GERRIT_USER} git push origin refs/heads/$i:$i --force || echo "No push to repo";
              fi
            done
            cd /opt/git
            rm -rf /opt/git/source_repo
            rm -rf /opt/git/dst_repo
        fi
done

# check if GERRIT_SITE is not empty and enforce reindex
if [ "$(ls -A $GERRIT_SITE/git/All-* &2>&1)" ]; then
  cd $GERRIT_SITE
  echo "Reindexing Gerrit repositories"
  su-exec ${GERRIT_USER} java -jar $GERRIT_SITE/bin/gerrit.war reindex
fi

git config --global user.email "you@example.com"
git config --global user.name "Admin"

## TODO: update codebases with new gerrit URL
#mkdir -p /tmp/libraries/registry-regulations-publication-pipeline
#cd /tmp/libraries/registry-regulations-publication-pipeline
#git clone /var/gerrit/review_site/git/libraries/registry-regulations-publication-pipeline.git
#cd registry-regulations-publication-pipeline
#for codebase in $(grep -r -l 'cicd2' ./); do
#  sed -i "s#https://gerrit-mdtu-ddm-edp-cicd.apps.cicd2.mdtu-ddm.projects.epam.com#${WEBURL}#g" ${codebase}
#  git commit -a -m 'fixed codebase gerrit URL' || echo "Nothing to commit";
#  git push || echo "No push to repo";
#done
## end of TODO