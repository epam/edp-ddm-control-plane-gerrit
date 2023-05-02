#!/usr/bin/env bash
#TODO Remove nooba IP
if [[ $(oc get clusterversion --no-headers) =~ ([0-9]+.[0-9]+) ]]; then
  clusterVersion=${BASH_REMATCH[1]}
fi

LIST="$(oc get cm -n control-plane ip-restrictions -o jsonpath='{.data.ip_list}')"
if [[ "${clusterVersion}" < "4.12" ]]; then
  sleep 30
  oc annotate --overwrite -n openshift-ingress svc/router-default service.beta.kubernetes.io/load-balancer-source-ranges="${LIST}"
  error_annotate_default_router=$(oc annotate --overwrite -n openshift-ingress svc/router-default service.beta.kubernetes.io/load-balancer-source-ranges="${LIST}" 2>&1)
else
  oc patch ingresscontroller default -n openshift-ingress-operator --type='json' -p='[{"op": "add", "path": "/spec/endpointPublishingStrategy/loadBalancer/allowedSourceRanges", "value":['${LIST}']}]'
fi
echo "next IP list ${LIST} was added to annotation"
