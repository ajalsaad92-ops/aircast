import { useCallback, useEffect, useRef, useState } from 'react';
import type { PluginListenerHandle } from '@capacitor/core';
import { AirCast, type MirrorOfferEvent } from '../lib/aircast';

export type MirrorState = 'idle' | 'negotiating' | 'live' | 'failed';

interface MirrorSession {
  id: string;
  name: string;
  ip: string;
}

/**
 * Makes this WebView the receiving end of a WebRTC screen share.
 *
 * The sender is an ordinary browser tab on the user's laptop or phone; the offer
 * arrives from the native layer, the answer goes back the same way. No native WebRTC
 * stack is involved, which is the whole reason this design fits inside Capacitor.
 */
export function useMirror(localIp: string | undefined, enabled: boolean) {
  const [state, setState] = useState<MirrorState>('idle');
  const [session, setSession] = useState<MirrorSession | null>(null);
  const [stream, setStream] = useState<MediaStream | null>(null);

  const pcRef = useRef<RTCPeerConnection | null>(null);
  const idRef = useRef<string | null>(null);
  const ipRef = useRef<string | undefined>(localIp);
  ipRef.current = localIp;

  const teardown = useCallback((next: MirrorState = 'idle') => {
    const pc = pcRef.current;
    pcRef.current = null;
    idRef.current = null;
    if (pc) {
      pc.onicecandidate = null;
      pc.ontrack = null;
      pc.onconnectionstatechange = null;
      try {
        pc.close();
      } catch {
        /* already closed */
      }
    }
    setStream(null);
    setSession(null);
    setState(next);
  }, []);

  /**
   * Chromium replaces host candidates with `<uuid>.local` mDNS names unless the page
   * has been granted a media permission. The peer at the other end of the LAN usually
   * cannot resolve those, and the connection silently never completes. We already
   * know this device's address, so we substitute it back in — both in the answer and
   * in every candidate we trickle out.
   */
  const derandomise = useCallback((sdp: string): string => {
    const ip = ipRef.current;
    if (!ip || ip === '127.0.0.1') return sdp;
    return sdp.replace(
      /[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}\.local/gi,
      ip,
    );
  }, []);

  const accept = useCallback(
    async (offer: MirrorOfferEvent) => {
      teardown('negotiating');
      idRef.current = offer.id;
      setSession({ id: offer.id, name: offer.name, ip: offer.ip });

      try {
        const pc = new RTCPeerConnection({ iceServers: [], bundlePolicy: 'max-bundle' });
        pcRef.current = pc;

        pc.ontrack = (event) => {
          const incoming = event.streams[0] ?? new MediaStream([event.track]);
          setStream(incoming);
        };

        pc.onicecandidate = (event) => {
          const id = idRef.current;
          if (!event.candidate || !id) return;
          const json = event.candidate.toJSON();
          void AirCast.mirrorCandidate({
            id,
            candidate: { ...json, candidate: derandomise(json.candidate ?? '') },
          }).catch(() => undefined);
        };

        pc.onconnectionstatechange = () => {
          switch (pc.connectionState) {
            case 'connected':
              setState('live');
              break;
            case 'failed':
              setState('failed');
              break;
            case 'closed':
              setState('idle');
              break;
            default:
              break;
          }
        };

        await pc.setRemoteDescription({ type: 'offer', sdp: offer.sdp });
        const answer = await pc.createAnswer();
        await pc.setLocalDescription(answer);

        const localSdp = pc.localDescription?.sdp ?? answer.sdp ?? '';
        await AirCast.mirrorAnswer({ id: offer.id, sdp: derandomise(localSdp) });
      } catch (error) {
        console.error('mirror negotiation failed', error);
        teardown('failed');
      }
    },
    [derandomise, teardown],
  );

  const stop = useCallback(async () => {
    const id = idRef.current;
    teardown('idle');
    await AirCast.mirrorEnd(id ? { id } : {}).catch(() => undefined);
  }, [teardown]);

  useEffect(() => {
    if (!enabled) {
      teardown('idle');
      return;
    }

    let cancelled = false;
    const handles: PluginListenerHandle[] = [];

    (async () => {
      const registered = [
        await AirCast.addListener('mirrorOffer', (offer) => void accept(offer)),
        await AirCast.addListener('mirrorCandidate', ({ id, candidate }) => {
          if (idRef.current !== id) return;
          pcRef.current?.addIceCandidate(candidate).catch(() => undefined);
        }),
        await AirCast.addListener('mirrorStopped', ({ id }) => {
          if (idRef.current === id) teardown('idle');
        }),
      ];
      if (cancelled) {
        registered.forEach((h) => void h.remove());
        return;
      }
      handles.push(...registered);
    })();

    return () => {
      cancelled = true;
      handles.forEach((h) => void h.remove());
    };
  }, [enabled, accept, teardown]);

  useEffect(() => () => teardown('idle'), [teardown]);

  return { state, session, stream, stop };
}
