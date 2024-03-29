{{/*
Expand the name of the chart.
*/}}
{{- define "gerrit-operator.name" -}}
{{- default .Chart.Name .Values.nameOverride | trunc 63 | trimSuffix "-" }}
{{- end }}

{{/*
Create chart name and version as used by the chart label.
*/}}
{{- define "gerrit-operator.chart" -}}
{{- printf "%s-%s" .Chart.Name .Chart.Version | replace "+" "_" | trunc 63 | trimSuffix "-" }}
{{- end }}

{{/*
Common labels
*/}}
{{- define "gerrit-operator.metaLabels" -}}
helm.sh/chart: {{ include "gerrit-operator.chart" . }}
{{ include "gerrit-operator.selectorLabels" . }}
{{- if .Chart.AppVersion }}
app.kubernetes.io/version: {{ .Chart.AppVersion | quote }}
{{- end }}
app.kubernetes.io/managed-by: {{ .Release.Service }}
{{- end }}

{{/*
Selector labels
*/}}
{{- define "gerrit-operator.selectorLabels" -}}
app.kubernetes.io/name: {{ include "gerrit-operator.name" . }}
app.kubernetes.io/instance: {{ .Release.Name }}
{{- end }}

{{- define "edp.hostnameSuffix" -}}
{{- printf "%s-%s.%s" .Values.cdPipelineName .Values.cdPipelineStageName .Values.dnsWildcard }}
{{- end }}


{{- define "gerrit.hostname" -}}
{{- $hostname := printf "%s-%s" "gerrit" .Release.Namespace }}
{{- printf "%s-%s" $hostname (include "edp.hostnameSuffix" .) }}
{{- end }}

{{- define "gerrit.url" -}}
{{- printf "%s%s" "https://" (include "gerrit.hostname" .) }}
{{- end }}

{{/*
If condition is required to save old CI git user name for existing envs till 1.9.6.
Since new user name is forced by upgrade gerrit-operator to v2.14.0
*/}}
{{- define "gerrit.gitUser" }}
{{- $gitServer := (lookup "v2.edp.epam.com/v1alpha1" "GitServer" .Release.Namespace "gerrit") -}}
{{- if $gitServer }}
{{- $gitServer.spec.gitUser }}
{{ else -}}
edp-ci
{{- end -}}
{{ end -}}

{{- define "keycloak.realm" -}}
{{- printf "%s-%s" .Release.Namespace .Values.keycloakIntegration.realm }}
{{- end -}}

{{- define "imageRegistry" -}}
{{- if .Values.global.imageRegistry -}}
{{- printf "%s/" .Values.global.imageRegistry -}}
{{- else -}}
{{- end -}}
{{- end }}
