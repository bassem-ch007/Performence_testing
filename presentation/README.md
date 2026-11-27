# Workshop performance presentation

Static, slide-like website for explaining the five independent Azure k3s performance workshop pipelines. No build step, package manager or frontend framework is required.

## Run locally (WSL/Linux)

```bash
cd ~/Performence_testing
git pull --ff-only origin main
python3 -m http.server 8000 --directory presentation
```

Open **http://localhost:8000**. Navigate with the left sidebar, the Previous/Next controls, or the keyboard arrows. All diagrams are HTML/CSS and are visible without external images. The preferred web font loads from Google when online; system font fallback works offline.

## Presenter's sequence

1. Roadmap: goals of five independent experiments.
2. Pipeline 01: monolith and the simplicity/limits of one deployment unit.
3. Pipeline 02: separate services, REST versus gRPC and shared workshop database.
4. Pipeline 03: three healthy product pods versus the distribution of traffic.
5. Stage 03 finding: measured baseline gRPC concentration in one product pod; JMeter overload failures **not attributed solely to gRPC**.
6. Pipeline 04: corrected order-service via headless DNS + round_robin; results require a matched fresh test.
7. Pipeline 05: CI builds/publishes a versioned Docker image; CD rolls product-service to the new version.
8. Rollout: demonstrate JMeter traffic during replacement; maxSurge=1, maxUnavailable=0; measure client-visible errors.
9. Recap: what is established by evidence and what remains to be measured.

## Grounding and limits

Observed baseline from Stage 03 screenshot and JMeter log (27 Nov 2026): three healthy product targets, approximately 3.26k completed product gRPC calls seen on one pod during the displayed time range, ~73.5k JMeter samples with ~50.8k failures (69.11%) at the final provided checkpoint under 2,550 active threads. Stage 04 and Stage 05 success are **not** asserted without fresh corresponding measurements. The charts/diagrams are explanatory static illustrations, not live Grafana data.

Stage 03 and Stage 04 Grafana dashboard definitions are in `k8s/base/monitoring/workshop-grpc-{scaling,correction}.json`; use equal JMeter conditions for comparison. The application images and pipelines are in this same repository. The live workshop is operated through GitHub Actions, not through this presentation.
