# k3s commands

Copy and execute these commands **inside the Azure VM**. Focus: k3s, Kubernetes namespaces, and Kubernetes resources. No JMeter, Docker Hub, Grafana URLs, or workshop pipeline commands.

## 1. Configure kubectl

```bash
export KUBECONFIG="$HOME/.kube/config"
```

```bash
kubectl cluster-info
kubectl get nodes -o wide
```

## 2. Namespaces

Show all namespaces:

```bash
kubectl get namespaces
```

Show resources in a specific namespace:

```bash
kubectl get all -n staging
kubectl get all -n data
kubectl get all -n monitoring
```

Show pods across **all namespaces**:

```bash
kubectl get pods --all-namespaces -o wide
```

Describe a namespace:

```bash
kubectl describe namespace staging
```

Set the **default namespace** for the current Kubernetes context:

```bash
kubectl config set-context --current --namespace=staging
```

Verify the active namespace:

```bash
kubectl config view --minify -o jsonpath='{..namespace}{"\n"}'
```

Switch the default namespace:

```bash
kubectl config set-context --current --namespace=monitoring
```

Restore `staging`:

```bash
kubectl config set-context --current --namespace=staging
```

> Explicit `-n staging`, `-n data`, or `-n monitoring` overrides the default namespace. `-A` means all namespaces.

## 3. Pods

```bash
kubectl get pods -n staging
kubectl get pods -n staging -o wide
kubectl get pods -n data
kubectl get pods -n monitoring
kubectl describe pods -n staging
```

Monitor running pods:

```bash
watch -n 2 'kubectl get pods -n staging -o wide'
```

## 4. Deployments, ReplicaSets, StatefulSets and DaemonSets

```bash
kubectl get deployments -n staging
kubectl get replicasets -n staging
kubectl get statefulsets -n data
kubectl get daemonsets -n monitoring
```

Inspect a deployment:

```bash
kubectl describe deployment product-service -n staging
```

See replica numbers and deployed images:

```bash
kubectl get deployments -n staging -o custom-columns='NAME:.metadata.name,DESIRED:.spec.replicas,READY:.status.readyReplicas,AVAILABLE:.status.availableReplicas,IMAGE:.spec.template.spec.containers[*].image'
```

## 5. Services and networking resources

```bash
kubectl get services -n staging
kubectl get services -n data
kubectl get services -n monitoring
kubectl get endpointslices -n staging
kubectl get networkpolicies -A
```

Inspect an individual Service:

```bash
kubectl describe service product-service -n staging
```

## 6. Logs, events and diagnostics

```bash
kubectl logs deployment/order-service -n staging --tail=100
```

```bash
kubectl logs -f deployment/order-service -n staging --tail=50
```

```bash
kubectl get events -n staging --sort-by=.lastTimestamp
kubectl get events -A --field-selector type=Warning --sort-by=.lastTimestamp
```

Describe a particular pod (replace the placeholder with a name from `kubectl get pods`):

```bash
kubectl describe pod POD_NAME -n staging
```

## 7. CPU and memory

```bash
kubectl top nodes
kubectl top pods -n staging
kubectl top pods -A
```

```bash
watch -n 3 'kubectl top pods -n staging'
```

> `kubectl top` requires Kubernetes Metrics Server.

## 8. Storage, configuration and permissions

```bash
kubectl get persistentvolumeclaims -A
kubectl get persistentvolumes
kubectl get configmaps -n staging
kubectl get secrets -n staging
kubectl get serviceaccounts -n staging
kubectl get roles,rolebindings -n staging
kubectl get clusterroles,clusterrolebindings
```

> Listing Secrets shows their names and types, not their values. Avoid printing Secret contents in shared terminals.

## 9. Rollout status and history

```bash
kubectl rollout status deployment/product-service -n staging
kubectl rollout history deployment/product-service -n staging
kubectl get deployments -n staging -o wide
```

## 10. Commands that MODIFY cluster state

These commands are **not read-only**. Execute only when you intentionally want to change Kubernetes.

Create a namespace (example only):

```bash
kubectl create namespace demo
```

Scale a deployment:

```bash
kubectl scale deployment/product-service --replicas=3 -n staging
```

Restart a deployment:

```bash
kubectl rollout restart deployment/product-service -n staging
```

Undo the previous rollout, if rollout history allows it:

```bash
kubectl rollout undo deployment/product-service -n staging
```

Delete the example namespace (never apply to workshop namespaces):

```bash
kubectl delete namespace demo
```

> Do **not** delete `staging`, `data`, or `monitoring`: deleting a namespace also deletes its namespaced resources. Do not reapply the workshop's baseline Kustomize overlay during an active test, because its staging deployments may be scaled to zero.
