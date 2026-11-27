# GitHub Actions — five manual stages, matching Grafana 01–05

These are **five separate manually dispatched workflows**, each with an independent description in its YAML file, corresponding to a Grafana dashboard. They reuse a single safeguarded SSH step engine and a Kubernetes script. **No instructor SSH session is needed per stage once the GitHub connection is configured.**

| GitHub Actions workflow | Corresponding Grafana view | Effect |
| --- | --- | --- |
| **01 · Monolith — load & spike** (`workshop-01.yaml`) | 01 Monolith | Set monolith 1 replica and stop all three microservices; verify monolith health |
| **02 · Microservices — load & spike** (`workshop-02.yaml`) | 02 Microservices | Stop monolith, reset order/product to original 1.0.0 images and original gRPC target, set user/product/order to one replica, health check |
| **03 · Manual scaling — baseline gRPC** (`workshop-03.yaml`) | 03 Scaling | Require baseline order/product and running microservices; scale product to 3 replicas, health check |
| **04 · gRPC correction — deploy v2** (`workshop-04.yaml`) | 04 Correction | Require 3 product replicas, deploy an already-published corrected order-service image with headless DNS target and round-robin setting; check health |
| **05 · Rolling update — CI/CD and availability** (`workshop-05.yaml`) | 05 Rolling update | CI builds/publishes **new** product-service image tag, CD updates product replicas with maxSurge 1 / maxUnavailable 0; GitHub runner probes public HTTP health throughout rollout |

Grafana dashboard 00 (cluster overview) can remain open through the whole workshop. JMeter runs on participants' machines, NOT in these orchestration workflows.

## One-time setup before clicking any stage

**1. GitHub visibility:** GitHub's Actions tab usually requires the manual-dispatch workflow to exist on the **default branch** to list/enable its `Run workflow` button. These files are currently on `workshop/k3s-azure-staging`. Review and merge them to `main` at the appropriate point; **do not merge blindly**, because other collaborators are working in parallel. When running, select the matching tested branch/ref. Never assume an unmerged workflow will appear in the Actions list.

**2. GitHub Environment:** Settings → Environments → create `workshop-staging`. Configure:
- Environment variables `WORKSHOP_SSH_HOST=20.240.55.238`, `WORKSHOP_SSH_USER=bassem`, `WORKSHOP_PUBLIC_IP=20.240.55.238`. Update if the VM's public IP changes.
- Environment secret `WORKSHOP_SSH_PRIVATE_KEY`: dedicated restricted SSH private key for a user allowed to run `kubectl` on this VM. Prefer a separate deployment key, not a general-purpose personal identity.
- Environment secret `WORKSHOP_SSH_KNOWN_HOSTS`: the SSH server host-key line validated from your existing trusted SSH connection; do not trust an unauthenticated public key scan.
- Protect the environment with an instructor approval rule if that feature is available for the repo/account. Revoke the deploy key after the workshop.

**3. GitHub repository secrets** (for workflow 05 CI): `DOCKERHUB_USERNAME` and `DOCKERHUB_TOKEN`, already used for the image publishing workflow. The repository must allow read/write access to the appropriate Docker Hub `bassem00/perf-product-service` image.

**4. SSH networking:** The **GitHub-hosted** job must reach Azure public TCP/22 through the VM's Azure NSG. The user's laptop reaching SSH does **not** demonstrate GitHub runner reachability. GitHub-hosted runner source addresses change. Don't globally expose SSH by default; prefer a controlled dedicated self-hosted runner with local kubectl access or a limited-time firewall rule and dedicated deploy credential, then revoke it. If the runner cannot SSH, these workflows will fail *before* making cluster changes. Once network access is configured, test stage 01.

**5. App/public access:** `k8s/workshop-access/nodeports.yaml` and `monitoring-nodeports.yaml` must already be applied. Allow HTTP application 30080–30083, Grafana 30300 and Prometheus 30090 in the Azure NSG. HTTP external `/actuator/health` for product on 30082 must work for workflow 05's availability polling.

**6. Corrected gRPC release:** Stage 04 is deployment-only. The new `bassem00/perf-order-service:1.1.0` image must **actually exist** and contain the fix from the separate `workshop/grpc-round-robin-v2` source. Build and publish it once with the pre-existing `publish-workshop-image.yaml` workflow or another verified CI build; do not assume that choosing `1.1.0` magically builds it. Stage 04 updates both order-service image and `PRODUCT_GRPC_TARGET` to `dns:///product-service-grpc-headless:9092`.

## Recommended class clicks

1. Actions → `01 · Monolith — load & spike` → Run workflow; dashboard 01, run JMeter monolith.
2. Actions → `02 · Microservices — load & spike` → Run workflow; dashboard 02, run JMeter microservices baseline.
3. Actions → `03 · Manual scaling — baseline gRPC` → Run workflow; dashboard 03, rerun the *same* microservices order-heavy profile and measure gRPC requests by pod.
4. Actions → `04 · gRPC correction — deploy v2` → Run workflow → enter **already-published** corrected order image tag; dashboard 04; rerun identical JMeter profile with 3 product pods.
5. Actions → `05 · Rolling update — CI/CD and availability` → Run workflow → enter a **new unused** product image tag and a source ref; dashboard 05, keep JMeter running during deployment. CI builds/pushes before CD; the GitHub runner checks public HTTP health at 1-second intervals during deployment. A successful check does **not** prove all client requests succeeded: compare JMeter JTL failure rate and Grafana errors.

All workflows report status in GitHub Actions. Before running each load test, wait for a green stage job and corresponding healthy endpoints. Stage 04 requires Stage 03; Stage 05 requires product replicas=3. Stage 02 resets baseline so you can repeat experiments without leaving the corrected version in place.

## Limitations

- Only one staging architecture is intended active at a time. This uses `kubectl scale`, not `kubectl apply -k k8s/overlays/azure`, which would reset deployments to 0 replicas.
- No one-pod dominance is presumed. Measure per-pod gRPC request distribution, compare fixed load, and identify the actual result.
- The correction image is **not yet confirmed published**, and GitHub SSH access from hosted runners has **not yet been tested**. These workflows are committed configuration, not evidence of successful live execution.
- The 05 HTTP monitor checks only `/actuator/health` for product, not a complete shopping transaction or the order gRPC path; a separate JMeter run supplies end-to-end evidence.
- This GitHub workflow engine uses SSH from Actions to your VM. If you want to avoid making TCP/22 publicly reachable from GitHub-hosted runners, run a GitHub **self-hosted runner** on the VM under a restricted account and adapt the engine to call `kubectl` locally (outbound-only GitHub connection).

## Safety audit after successful Stage 01 (27 Nov 2026)
The monolith is running 1/1, while user/product/order remain scaled 0/0 (verified by instructor from Azure). The following safeguards were added to `main` **after** Stage 01:
- Every stage uses the readable user kubeconfig `~/.kube/config` in noninteractive SSH.
- Stage 02 checks database readiness, staging Secret, all three Deployments and Services **before** switching off the monolith; it restores baseline order/product image, gRPC target, and one replica each.
- Stage 03 requires 1 ready microservice replica each, baseline images, and the baseline gRPC target before scaling product-service to 3.
- Stage 04 requires the correct original image baseline and healthy 3-product-replica topology. It updates the order image and gRPC target together, then restores the prior image and *both* gRPC settings if the rollout or health check fails.
- Stage 05 now executes a read-only **05-preflight** job before any Docker image is built. The build is skipped unless 3 healthy product replicas and the corrected order-service are ready. After CI, CD monitors public HTTP health during the rolling update and restores the previous product image if the rollout fails.
- Health availability polling is **HTTP /actuator/health only**, at roughly one probe per second during the rollout; it does not prove zero failed JMeter transactions or successful gRPC behavior. Compare the JMeter JTL and Grafana panels.
- There are still no verified live results for stages 02–05; do not claim the workflows are fully validated until each succeeds.

**Operational note:** Stage 02 involves an intentional architecture cutover, not zero downtime. The preflight reduces avoidable errors but cannot guarantee an automatic switchback if pods fail to start after monolith is stopped. Stage 04 needs the prepublished fixed order-service image; Stage 05 still needs the Docker Hub credentials and a new unused product-service image tag.
