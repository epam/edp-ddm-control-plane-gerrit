#!/bin/bash

oc create namespace $1 --dry-run=true -o yaml | \
  oc apply -f -
