import test from 'node:test';
import assert from 'node:assert/strict';
import {
  advanceOrCollapseIsland,
  clampSettings,
  collapseIsland,
  createInitialIslandState,
  enqueueIslandEvent,
  getIslandDimensions,
  longPressIsland,
  resetPositionAndSize,
  resolveIncomingCall,
  tapIsland,
  toggleMusicPlayPause,
} from './islandReducer';
import { createDefaultSettings } from './types';

test('queues incoming transient events when another non-music card is active and swaps cleanly', () => {
  const settings = createDefaultSettings();
  let state = createInitialIslandState();

  state = enqueueIslandEvent(state, { id: '1', type: 'CHARGING_STARTED', batteryPercent: 72 }, settings);
  assert.equal(state.displayMode, 'COMPACT');
  assert.equal(state.activeEvent?.type, 'CHARGING_STARTED');
  assert.equal(state.queue.length, 0);

  state = enqueueIslandEvent(state, { id: '2', type: 'WIFI_CONNECTED', ssid: 'Studio-5G' }, settings);
  assert.equal(state.activeEvent?.type, 'CHARGING_STARTED');
  assert.equal(state.queue.length, 1);

  state = advanceOrCollapseIsland(state);
  assert.equal(state.activeEvent?.type, 'WIFI_CONNECTED');
  assert.equal(state.queue.length, 0);

  state = advanceOrCollapseIsland(state);
  assert.equal(state.activeEvent, null);
  assert.equal(state.displayMode, 'IDLE');
});

test('music playback persists in compact mode after transient notifications finish', () => {
  const settings = createDefaultSettings();
  let state = createInitialIslandState();

  state = enqueueIslandEvent(
    state,
    {
      id: 'm1',
      type: 'MUSIC_PLAYER',
      track: { title: 'Midnight Refraction', artist: 'Kiasmos', isPlaying: true, positionSec: 40, durationSec: 210 },
    },
    settings
  );
  assert.equal(state.displayMode, 'COMPACT');
  assert.equal(state.activeEvent?.type, 'MUSIC_PLAYER');

  state = enqueueIslandEvent(
    state,
    { id: 'b1', type: 'BLUETOOTH', deviceName: 'Sony WH-1000XM5', connected: true, isAudio: true },
    settings
  );
  assert.equal(state.activeEvent?.type, 'BLUETOOTH');

  state = advanceOrCollapseIsland(state);
  assert.equal(state.activeEvent?.type, 'MUSIC_PLAYER');
  assert.equal(state.displayMode, 'COMPACT');
});

test('tap expands compact card, long press opens full expanded, collapse returns to idle', () => {
  const settings = createDefaultSettings();
  let state = createInitialIslandState();

  state = enqueueIslandEvent(state, { id: '1', type: 'BATTERY_FULL', batteryPercent: 100 }, settings);
  state = tapIsland(state);
  assert.equal(state.displayMode, 'EXPANDED');

  state = longPressIsland(state);
  assert.equal(state.displayMode, 'FULL_EXPANDED');

  state = collapseIsland(state);
  assert.equal(state.displayMode, 'IDLE');
  assert.equal(state.activeEvent, null);
});

test('disabled events and master off are respected', () => {
  const settings = createDefaultSettings();
  settings.enabledEvents.HOTSPOT = false;

  let state = createInitialIslandState();
  state = enqueueIslandEvent(state, { id: 'h1', type: 'HOTSPOT', enabled: true }, settings);
  assert.equal(state.activeEvent, null);

  settings.enabledEvents.HOTSPOT = true;
  settings.masterEnabled = false;
  state = enqueueIslandEvent(state, { id: 'h2', type: 'HOTSPOT', enabled: true }, settings);
  assert.equal(state.activeEvent, null);
});

test('incoming call preempts active events and resolves cleanly', () => {
  const settings = createDefaultSettings();
  let state = createInitialIslandState();
  state = enqueueIslandEvent(state, { id: '1', type: 'CHARGING_STARTED', batteryPercent: 50 }, settings);
  state = enqueueIslandEvent(
    state,
    { id: 'c1', type: 'INCOMING_CALL', callerName: 'Elena Vance', phoneNumber: '+1 415 555 0194' },
    settings
  );
  assert.equal(state.activeEvent?.type, 'INCOMING_CALL');
  assert.equal(state.displayMode, 'EXPANDED');

  state = resolveIncomingCall(state, 'ACCEPT');
  assert.equal(state.displayMode, 'IDLE');
  assert.equal(state.callStatusMessage, 'Connected with Elena Vance');
});

test('settings clamping, resetToDefault, music toggle, and dimensions behave deterministically', () => {
  const base = createDefaultSettings();
  const modified = clampSettings({ offsetX: 999, offsetY: -10, idleWidth: 500, cornerRadius: 5 }, base);
  assert.equal(modified.offsetX, 80);
  assert.equal(modified.offsetY, 4);
  assert.equal(modified.idleWidth, 200);
  assert.equal(modified.cornerRadius, 10);

  const reset = resetPositionAndSize(modified);
  assert.equal(reset.offsetX, base.offsetX);
  assert.equal(reset.offsetY, base.offsetY);
  assert.equal(reset.idleWidth, base.idleWidth);

  const dims = getIslandDimensions('IDLE', null, reset);
  assert.deepEqual(dims, { width: 126, height: 36, radius: 24 });

  let state = createInitialIslandState();
  state = enqueueIslandEvent(
    state,
    {
      id: 'm1',
      type: 'MUSIC_PLAYER',
      track: { title: 'Echoes', artist: 'Rival Consoles', isPlaying: true, positionSec: 12, durationSec: 180 },
    },
    base
  );
  state = toggleMusicPlayPause(state);
  assert.equal(state.persistentMusic?.isPlaying, false);
});
