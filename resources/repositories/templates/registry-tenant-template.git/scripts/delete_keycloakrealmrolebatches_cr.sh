#!/usr/bin/env bash

export NAMESPACE=$1

if [[ ${NAMESPACE} == '' ]]; then
  echo "NAMESPACE is not defined, exiting"
  exit 1
fi

echo "Removing keycloakrealmrolebatches in '${NAMESPACE}':"
while [[ `oc get keycloakrealmrolebatches.v1.edp.epam.com -n ${NAMESPACE} --no-headers --output=custom-columns=NAME:.metadata.name` != '' ]]; do
  sleep 5
  oc get keycloakrealmrolebatches.v1.edp.epam.com -n ${NAMESPACE} --no-headers --output=custom-columns=NAME:.metadata.name | xargs -r oc delete keycloakrealmrolebatches.v1.edp.epam.com -n ${NAMESPACE}
done


