# FlashHead shared-weight dual-session implementation

V2 was deployed on 2026-10-15 after the user explicitly authorized restarting production. API 8001 (PID 2571463) and FlashHead 8766 (PID 2571462) passed dual-user production-port acceptance. V1 and the exact predeployment backup remain available for rollback.

The bridge loads one set of model weights. Each connection owns a shallow pipeline shell, reference features, dimensions, motion history, random generator, CPU/CUDA RNG state and audio buffer. A single GPU executor serializes preparation and audio chunks; cancellation drains executing GPU work before releasing the lock. The template pipeline never prepares a reference or generates frames.

`FLASHHEAD_MAX_SESSIONS=2` controls bridge capacity. OpenTalking enables its separate dual FlashHead pool with `OPENTALKING_FLASHHEAD_MAX_SESSIONS=2`; the default value of 1 retains the previous single-slot path. FlashTalk retains its original single-session limit. The API uses the selected model's capacity check.

Validation on the GPU host: 3 context/cancellation tests and 54 relevant OpenTalking tests passed. Session API tests have the same 50 passes and 10 failures on V1 and V2. After compiler warmup, three A chunks were byte-identical between isolated and interleaved execution with B. Both 576×576 and 576×768 JPEG dimensions were checked. Two streams carrying 8.64 seconds of audio each completed in 6.72 seconds; average chunk response times were 0.346 and 0.745 seconds. Two real WebRTC peers received 802 video and 826 audio frames in total, with nonzero audio. Interrupting or closing A did not prevent B completing its text. Queued deletion and capacity reuse passed.

The first cold-state strict comparison failed; byte-identical evidence applies to the warmed samples, not all compiler states. Production-port acceptance received 772 video and 815 audio frames, verified isolated interruption and continued B playback after A left, and passed queued deletion. Interrupt HTTP latency was about 0.95 seconds. Test-created sessions were closed afterward. The post-test whole-GPU reading was 19473 MiB, not a peak measurement. See production-dual-webrtc-result.json.

This is short-duration server-side validation, not a mobile listening or sustained load test. PyTorch allocated memory was about 4.02 GiB after two session initializations; the isolated warmed process used about 7.3 GiB according to nvidia-smi, including additional runtime/cache memory. These are observations, not peak guarantees.

Only FlashHead Lite, one GPU and one unified API process were validated. Distributed admission, more than two users and simultaneous full-load FlashTalk are outside this validation.

GPU host checkout: `/home/nm/develop/code/experiments/shared-flashhead-v2/`. Exact predeployment rollback data: `/home/nm/develop/code/backups/shared-flashhead-v2-predeploy/`. Private environment data stays on the server.

After explicit authorization to restart production, run `python3 deploy.py install` from that checkout. Wait for bridge warmup and API readiness, then run `opentalking/.venv/bin/python verify_dual_webrtc.py --port 8001 --output production-dual-webrtc-result.json`. If acceptance fails, run `python3 deploy.py rollback`. Both operations restart the affected services. Source and process identity changes cause deployment to stop. A live rollback drill has not been performed.
