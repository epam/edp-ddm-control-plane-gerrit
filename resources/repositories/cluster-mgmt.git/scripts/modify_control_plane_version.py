import os
import sys
import yaml
from kubernetes import client, config
from kubernetes.client.rest import ApiException
from os.path import expanduser

home_dir = expanduser("~")
client_namespace = os.environ.get("globalEDPProject", "control-plane")
kubeconfig_path = os.environ.get("KUBECONFIG", home_dir + "/.kube/config")
cp_console_values_path = sys.argv[1]

codebase = {
    "group": "v2.edp.epam.com",
    "version": "v1alpha1",
    "namespace": client_namespace,
    "plural": "codebases"
}


def fetch_codebases_version(api_instance, group, version, namespace, plural):
    versions = []
    response = api_instance.list_namespaced_custom_object(group, version, namespace, plural)
    for codebases in response.get('items', []):
        if codebases.get('metadata', {}).get("name") != "cluster-mgmt":
            version_hyphen = codebases.get('spec', {}).get("jobProvisioning")
            split_version = version_hyphen.split('-')
            codebase_version = '.'.join(split_version[1:4])
            versions.append(codebase_version)
    unique_versions = list(set(versions))

    return unique_versions


def parse_console_versions(values_file, cp_values_file, allowed_values):
    filtered_list = []
    unique_data = []
    with open(values_file, 'r') as file:
        try:
            data = yaml.safe_load(file)
            console_versions = data.get('consoleVersions')

            if allowed_values:
                filtered_list = [d for d in console_versions if d['registryVersion'] in allowed_values]
            filtered_list.append(max(console_versions, key=lambda x: x['registryVersion']))

            # Find the maximum registryVersion

            for d in filtered_list:
                if d not in unique_data:
                    unique_data.append(d)

            max_version = max(d['registryVersion'] for d in unique_data)

            for d in unique_data:
                if d['registryVersion'] == max_version:
                    d['latest'] = True

            data['consoleVersions'] = unique_data

        except yaml.YAMLError as e:
            print(f"Error loading YAML file: {e}")
            return None

    with open(cp_values_file, 'r') as file:
        cp_values_data = yaml.safe_load(file)

    cp_values_data.update(data)

    with open(cp_values_file, 'w', encoding='utf8') as file:
        yaml.dump(cp_values_data, file, default_flow_style=False, allow_unicode=True)


def main():
    if os.path.isfile(kubeconfig_path):
        config.load_kube_config()
    else:
        config.load_incluster_config()

    api_instance = client.CustomObjectsApi()
    try:
        codebase_versions = fetch_codebases_version(api_instance, codebase['group'], codebase['version'],
                                                    codebase['namespace'], codebase['plural'])
        parse_console_versions("console-versions.yaml", cp_console_values_path, codebase_versions)
    except ApiException as e:
        print(f"Error updating values: {e}")


if __name__ == "__main__":
    main()
