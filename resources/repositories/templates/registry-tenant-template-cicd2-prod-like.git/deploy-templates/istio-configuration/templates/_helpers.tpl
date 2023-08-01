{{- define "portal.default.host" }}
{{- $root := .root }}
{{- $portalName := .portalName }}
{{- printf "%s-%s-%s.%s" $portalName $root.Values.cdPipelineName $root.Values.cdPipelineStageName $root.Values.dnsWildcard }}
{{- end }}

{{- define "officer-portal.url" -}}
{{ $host := ternary .Values.portals.officer.customDns.host (include "portal.default.host" (dict  "root" . "portalName" "officer-portal")) .Values.portals.officer.customDns.enabled }}
{{- $host }}
{{- end }}

{{- define "citizen-portal.url" -}}
{{ $host := ternary .Values.portals.citizen.customDns.host (include "portal.default.host" (dict  "root" . "portalName" "citizen-portal")) .Values.portals.citizen.customDns.enabled }}
{{- $host }}
{{- end }}

{{- define "notifications.diia.url" -}}
{{- regexReplaceAll "http(s?)://" .Values.global.notifications.diia.url "${2}" | replace "/" "" | default "api2t.diia.gov.ua" }}
{{- end }}