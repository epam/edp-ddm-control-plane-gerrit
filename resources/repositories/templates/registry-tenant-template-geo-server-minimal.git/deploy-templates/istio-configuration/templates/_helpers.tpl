{{- define "officer-portal.url" -}}
{{- .Values.portals.officer.customDns.host | default ( printf "%s-%s.%s" "officer-portal" .Values.stageName .Values.dnsWildcard )}}
{{- end }}

{{- define "citizen-portal.url" -}}
{{- .Values.portals.citizen.customDns.host | default ( printf "%s-%s.%s" "citizen-portal" .Values.stageName .Values.dnsWildcard ) }}
{{- end }}