FROM openfrontier/gerrit:3.3.2

COPY git /opt/git

COPY resources/scripts/add_template_repositories.sh /docker-entrypoint-init.d/
RUN chmod +x /docker-entrypoint-init.d/add_template_repositories.sh

#COPY resources/repositories /opt/repositories


