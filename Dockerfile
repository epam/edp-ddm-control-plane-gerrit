FROM openfrontier/gerrit:3.3.2

COPY git /opt/git
COPY --chmod=755 resources/scripts/add_template_repositories.sh /docker-entrypoint-init.d
