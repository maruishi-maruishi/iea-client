'use strict';

// Reverse of Minecraft 1.13's "flattening": maps NEW (1.13+) block/item texture
// file names back to the OLD (1.8.9) names, so a modern pack's textures land where
// 1.8.9's built-in models look for them. Names that never changed are absent here
// (the converter keeps those as-is), so this only needs the renamed ones.

// 1.13 colour order; the 1.8 name differs only for light_gray -> silver.
const COLORS = ['white', 'orange', 'magenta', 'light_blue', 'yellow', 'lime', 'pink',
  'gray', 'light_gray', 'cyan', 'purple', 'blue', 'brown', 'green', 'red', 'black'];
const c8 = (c) => (c === 'light_gray' ? 'silver' : c);

// wood types; 1.8 called dark_oak "big_oak".
const WOODS = ['oak', 'spruce', 'birch', 'jungle', 'acacia', 'dark_oak'];
const w8 = (w) => (w === 'dark_oak' ? 'big_oak' : w);

const BLOCK = {};
const put = (map, nw, old) => { map[nw] = old; };

// --- colour-driven blocks ---
for (const c of COLORS) {
  put(BLOCK, `${c}_wool`, `wool_colored_${c8(c)}`);
  put(BLOCK, `${c}_stained_glass`, `glass_${c8(c)}`);
  put(BLOCK, `${c}_stained_glass_pane_top`, `glass_pane_top_${c8(c)}`);
  put(BLOCK, `${c}_terracotta`, `hardened_clay_stained_${c8(c)}`);
}
// --- wood-driven blocks ---
for (const w of WOODS) {
  put(BLOCK, `${w}_planks`, `planks_${w8(w)}`);
  put(BLOCK, `${w}_log`, `log_${w8(w)}`);
  put(BLOCK, `${w}_log_top`, `log_${w8(w)}_top`);
  put(BLOCK, `${w}_leaves`, `leaves_${w8(w)}`);
  put(BLOCK, `${w}_sapling`, `sapling_${w8(w)}`);
  put(BLOCK, `${w}_door_top`, `door_${w8(w)}_upper`);
  put(BLOCK, `${w}_door_bottom`, `door_${w8(w)}_lower`);
  put(BLOCK, `${w}_trapdoor`, `${w === 'oak' ? 'trapdoor' : 'trapdoor_' + w8(w)}`);
}

// --- manual block renames (1.13 name -> 1.8 name) ---
Object.assign(BLOCK, {
  // stone variants
  granite: 'stone_granite',
  polished_granite: 'stone_granite_smooth',
  diorite: 'stone_diorite',
  polished_diorite: 'stone_diorite_smooth',
  andesite: 'stone_andesite',
  polished_andesite: 'stone_andesite_smooth',
  mossy_cobblestone: 'cobblestone_mossy',
  // stone bricks
  stone_bricks: 'stonebrick',
  mossy_stone_bricks: 'stonebrick_mossy',
  cracked_stone_bricks: 'stonebrick_cracked',
  chiseled_stone_bricks: 'stonebrick_carved',
  // grass / dirt
  grass_block_top: 'grass_top',
  grass_block_side: 'grass_side',
  grass_block_side_overlay: 'grass_side_overlay',
  grass_block_snow: 'grass_side_snowed',
  grass_path_top: 'grass_path_top',
  grass_path_side: 'grass_path_side',
  coarse_dirt: 'coarse_dirt',
  podzol_top: 'dirt_podzol_top',
  podzol_side: 'dirt_podzol_side',
  mycelium_top: 'mycelium_top',
  mycelium_side: 'mycelium_side',
  farmland: 'farmland_dry',
  farmland_moist: 'farmland_wet',
  // sandstone
  sandstone: 'sandstone_normal',
  chiseled_sandstone: 'sandstone_carved',
  cut_sandstone: 'sandstone_smooth',
  red_sandstone: 'red_sandstone_normal',
  chiseled_red_sandstone: 'red_sandstone_carved',
  cut_red_sandstone: 'red_sandstone_smooth',
  // bricks / nether
  bricks: 'brick',
  nether_bricks: 'nether_brick',
  red_nether_bricks: 'red_nether_brick',
  // misc common
  crafting_table_front: 'crafting_table_front',
  crafting_table_side: 'crafting_table_side',
  crafting_table_top: 'crafting_table_top',
  furnace_front: 'furnace_front_off',
  furnace_front_on: 'furnace_front_on',
  furnace_side: 'furnace_side',
  furnace_top: 'furnace_top',
  pumpkin_side: 'pumpkin_side',
  pumpkin_top: 'pumpkin_top',
  carved_pumpkin: 'pumpkin_face_off',
  jack_o_lantern: 'pumpkin_face_on',
  melon_side: 'melon_side',
  melon_top: 'melon_top',
  hay_block_side: 'hay_block_side',
  hay_block_top: 'hay_block_top',
  bookshelf: 'bookshelf',
  note_block: 'noteblock',
  jukebox_side: 'jukebox_side',
  jukebox_top: 'jukebox_top',
  redstone_lamp: 'redstone_lamp_off',
  redstone_lamp_on: 'redstone_lamp_on',
  // quartz
  quartz_block_side: 'quartz_block_side',
  quartz_block_top: 'quartz_block_top',
  quartz_block_bottom: 'quartz_block_bottom',
  chiseled_quartz_block: 'quartz_block_chiseled',
  chiseled_quartz_block_top: 'quartz_block_chiseled_top',
  quartz_pillar: 'quartz_block_lines',
  quartz_pillar_top: 'quartz_block_lines_top',
});

// --- item renames (1.13 item/ name -> 1.8 items/ name) ---
const ITEM = {};
// dyes: 1.13 "<colour>_dye" -> 1.8 "dye_powder_<colour8>" (real dye colours only)
for (const c of COLORS) put(ITEM, `${c}_dye`, `dye_powder_${c8(c)}`);
Object.assign(ITEM, {
  // records -> music discs
  music_disc_13: 'record_13',
  music_disc_cat: 'record_cat',
  music_disc_blocks: 'record_blocks',
  music_disc_chirp: 'record_chirp',
  music_disc_far: 'record_far',
  music_disc_mall: 'record_mall',
  music_disc_mellohi: 'record_mellohi',
  music_disc_stal: 'record_stal',
  music_disc_strad: 'record_strad',
  music_disc_ward: 'record_ward',
  music_disc_11: 'record_11',
  music_disc_wait: 'record_wait',
  // a few renamed items
  cooked_porkchop: 'cooked_porkchop',
  golden_apple: 'apple_golden',
  enchanted_golden_apple: 'apple_golden',
  fire_charge: 'fireball',
  gunpowder: 'gunpowder',
  slime_ball: 'slimeball',
  ender_pearl: 'ender_pearl',
  ender_eye: 'ender_eye',
  nether_star: 'nether_star',
  oak_door: 'door_wood',
  iron_door: 'door_iron',
  cauldron: 'cauldron',
  brewing_stand: 'brewing_stand',
  flint_and_steel: 'flint_and_steel',
  fishing_rod: 'fishing_rod',
  fishing_rod_cast: 'fishing_rod_cast',
});

function oldBlockName(name) { return BLOCK[name] || null; }
function oldItemName(name) { return ITEM[name] || null; }

module.exports = { oldBlockName, oldItemName };
