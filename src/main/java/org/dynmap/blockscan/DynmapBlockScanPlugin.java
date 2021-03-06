package org.dynmap.blockscan;


import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.dynmap.blockscan.BlockStateOverrides.BlockStateOverride;
import org.dynmap.blockscan.BlockStateOverrides.BlockTintOverride;
import org.dynmap.blockscan.blockstate.BlockState;
import org.dynmap.blockscan.blockstate.ModelRotation;
import org.dynmap.blockscan.blockstate.Variant;
import org.dynmap.blockscan.blockstate.VariantList;
import org.dynmap.blockscan.model.BlockElement;
import org.dynmap.blockscan.model.BlockFace;
import org.dynmap.blockscan.model.BlockModel;
import org.dynmap.blockscan.statehandlers.BedMetadataStateHandler;
import org.dynmap.blockscan.statehandlers.IStateHandler;
import org.dynmap.blockscan.statehandlers.IStateHandlerFactory;
import org.dynmap.blockscan.statehandlers.PistonMetadataStateHandler;
import org.dynmap.blockscan.statehandlers.SimpleMetadataStateHandler;
import org.dynmap.blockscan.statehandlers.SnowyMetadataStateHandler;
import org.dynmap.blockscan.statehandlers.StairMetadataStateHandler;
import org.dynmap.blockscan.statehandlers.StateContainer;
import org.dynmap.blockscan.statehandlers.StateContainer.StateRec;
import org.dynmap.blockscan.statehandlers.StateContainer.WellKnownBlockClasses;
import org.dynmap.blockscan.util.Matrix3D;
import org.dynmap.blockscan.util.Vector3D;
import org.dynmap.modsupport.BlockSide;
import org.dynmap.modsupport.BlockTextureRecord;
import org.dynmap.modsupport.CuboidBlockModel;
import org.dynmap.modsupport.ModModelDefinition;
import org.dynmap.modsupport.ModSupportAPI;
import org.dynmap.modsupport.ModTextureDefinition;
import org.dynmap.modsupport.PatchBlockModel;
import org.dynmap.modsupport.TextureFile;
import org.dynmap.modsupport.TextureModifier;
import org.dynmap.modsupport.TransparencyMode;
import org.dynmap.renderer.RenderPatchFactory.SideVisible;

import com.google.common.base.Charsets;
import com.google.common.collect.ImmutableMap;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonIOException;
import com.google.gson.JsonSyntaxException;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.MalformedJsonException;

import jline.internal.Log;

import org.dynmap.blockscan.statehandlers.DoorStateHandler;
import org.dynmap.blockscan.statehandlers.ForgeStateContainer;
import org.dynmap.blockscan.statehandlers.RedstoneWireStateHandler;
import org.dynmap.blockscan.statehandlers.NSEWUConnectedMetadataStateHandler;
import org.dynmap.blockscan.statehandlers.NSEWConnectedMetadataStateHandler;
import org.dynmap.blockscan.statehandlers.GateMetadataStateHandler;

import net.minecraft.block.Block;
import net.minecraft.block.properties.IProperty;
import net.minecraft.block.state.BlockStateContainer;
import net.minecraft.block.state.IBlockState;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.IStringSerializable;
import net.minecraft.util.ResourceLocation;
import net.minecraftforge.fml.common.Loader;
import net.minecraftforge.fml.common.ModContainer;

public class DynmapBlockScanPlugin
{
    public static OurLog logger = new OurLog();
    public static DynmapBlockScanPlugin plugin;
    
    private Map<EnumFacing, BlockSide> faceToSide = new HashMap<EnumFacing, BlockSide>();
    private BlockStateOverrides overrides;

    
    public static class BlockRecord {
    	public StateContainer sc;
    	public Map<StateRec, List<VariantList>> varList;	// Model references for block
    	public Set<String> renderProps;	// Set of render properties
    	public IStateHandler handler;		// Best handler
    }
    
    private IStateHandlerFactory[] state_handler = {
        new NSEWConnectedMetadataStateHandler(),
        new NSEWUConnectedMetadataStateHandler(),
        new RedstoneWireStateHandler(),
        new GateMetadataStateHandler(),
        new StairMetadataStateHandler(),
        new DoorStateHandler(),
        new PistonMetadataStateHandler(),
        new SnowyMetadataStateHandler(),
        new BedMetadataStateHandler(),
        new SimpleMetadataStateHandler()
    };

    public DynmapBlockScanPlugin(MinecraftServer srv)
    {
        plugin = this;
        
        faceToSide.put(EnumFacing.DOWN, BlockSide.FACE_0);
        faceToSide.put(EnumFacing.UP, BlockSide.FACE_1);
        faceToSide.put(EnumFacing.NORTH, BlockSide.FACE_2);
        faceToSide.put(EnumFacing.SOUTH, BlockSide.FACE_3);
        faceToSide.put(EnumFacing.WEST, BlockSide.FACE_4);
        faceToSide.put(EnumFacing.EAST, BlockSide.FACE_5);
    }


    public void onEnable() {
        logger.info("onEnable()");
    }
    public void onDisable() {
        logger.info("onDisable()");
    }
    public void serverStarted() {
    	logger.info("serverStarted()");
    }
    public void serverStarting() {
        logger.info("serverStarting()");
        
        // Load override resources
        InputStream override_str = openResource("dynmapblockscan", "blockstateoverrides.json");
        if (override_str != null) {
        	Reader rdr = new InputStreamReader(override_str, Charsets.UTF_8);
            GsonBuilder gb = new GsonBuilder(); // Start with builder
            gb.registerTypeAdapter(BlockTintOverride.class, new BlockTintOverride.Deserializer()); // Add Condition handler1
            Gson parse = gb.create();
            JsonReader jrdr = new JsonReader(rdr);
            jrdr.setLenient(true);
        	overrides = parse.fromJson(jrdr, BlockStateOverrides.class);
        	try {
				override_str.close();
			} catch (IOException e) {
			}
        }
        else {
        	logger.info("Failed to load block overrides");
        	overrides = new BlockStateOverrides();
        }
        // Scan other modules for block overrides
        for (Entry<String, ModContainer> mod : Loader.instance().getIndexedModList().entrySet()) {
            InputStream str = openResource(mod.getKey(), "assets/" + mod.getKey() + "/dynmap/blockstateoverrides.json");
            if (str != null) {
                Reader rdr = new InputStreamReader(str, Charsets.UTF_8);
                GsonBuilder gb = new GsonBuilder(); // Start with builder
                gb.registerTypeAdapter(BlockTintOverride.class, new BlockTintOverride.Deserializer()); // Add Condition handler1
                Gson parse = gb.create();
                try {
                    JsonReader jrdr = new JsonReader(rdr);
                    jrdr.setLenient(true);
                    BlockStateOverrides modoverrides = parse.fromJson(jrdr, BlockStateOverrides.class);
                    if (modoverrides != null) {
                        overrides.merge(modoverrides);
                        logger.info("Loaded dynmap overrides from " + mod.getKey());
                    }
                } catch (JsonIOException iox) {
                    logger.severe("Error reading dynmap overrides from " + mod.getKey(), iox);
                } catch (JsonSyntaxException sx) {
                    logger.severe("Error parsing dynmap overrides from " + mod.getKey(), sx);
                } finally {
                    if (str != null) { try { str.close(); } catch (IOException iox) {} }
                }
            }
        }


        Map<String, BlockRecord> blockRecords = new HashMap<String, BlockRecord>();

        // Now process models from block records
        Map<String, BlockModel> models = new HashMap<String, BlockModel>();
        
        // Scan blocks and block states
        for (Block b : Block.REGISTRY) {
            ResourceLocation rl = b.getRegistryName();
            //logger.info(String.format("Block %s: %d", rl, Block.getIdFromBlock(b)));
            BlockStateContainer bsc = b.getBlockState();
            // See if any of the block states use MODEL
            boolean uses_model = false;
            boolean uses_nonmodel = false;
            for (IBlockState bs : bsc.getValidStates()) {
            	switch (bs.getRenderType()) {
            		case MODEL:
            			uses_model = true;
            			break;
            		case INVISIBLE:
            			uses_nonmodel = true;
            			logger.info(String.format("%s: Invisible block - nothing to render", rl));
            			break;
            		case ENTITYBLOCK_ANIMATED:
            			uses_nonmodel = true;
            			logger.info(String.format("%s: Animated block - needs to be handled specially", rl));
            			break;
            		case LIQUID:
            			uses_nonmodel = true;
            			logger.info(String.format("%s: Liquid block - special handling", rl));
            			break;
            	}
            }
            // Not model block - nothing else to do yet
            if (!uses_model) {
            	continue;
            }
            else if (uses_nonmodel) {
            	logger.warning(String.format("%s: Block mixes model and nonmodel state handling!", rl));
            }
            // Generate property value map
            Map<String, List<String>> propMap = buildPropoertMap(bsc);
            // Try to find blockstate file
            BlockState blockstate = loadBlockState(rl.getResourceDomain(), rl.getResourcePath(), overrides, propMap);
            // Build block record
            BlockRecord br = new BlockRecord();
            // Process blockstate
        	if (blockstate != null) {
                br.renderProps = blockstate.getRenderProps();
        	}
        	// Build generic block state container for block
        	br.sc = new ForgeStateContainer(b, br.renderProps, propMap);
        	if (blockstate != null) {
            	br.varList = new HashMap<StateRec, List<VariantList>>();
        		// Loop through rendering states in state container
        		for (StateRec sr : br.sc.getValidStates()) {
        			List<VariantList> vlist = blockstate.getMatchingVariants(sr.getProperties(), models);
        			br.varList.put(sr, vlist);
        		}
        	}
        	else {
        	    br.varList = Collections.emptyMap();
        	}
            // Check for matching handler
            br.handler = null;
            for (IStateHandlerFactory f : state_handler) {
              	br.handler = f.canHandleBlockState(br.sc);
                if (br.handler != null) {
                    break;
                }
            }
            if (br.handler == null) {
                logger.info(rl + ":  NO MATCHING HANDLER");
            }
            blockRecords.put(rl.toString(), br);
        }
        
        logger.info("Loading models....");
        for (String blkname : blockRecords.keySet()) {
        	BlockRecord br = blockRecords.get(blkname);
        	if (br.sc != null) {
        		for (Entry<StateRec, List<VariantList>> var : br.varList.entrySet()) {
        			for (VariantList vl : var.getValue()) {
        				for (Variant va : vl.variantList) {
        					if (va.model != null) {
        						String[] tok = va.model.split(":");
        						if (tok.length == 1) {
        							tok = new String[] { "minecraft", "block/" + tok[0] };
        						}
        						else {
        							tok[1] = "block/" + tok[1];
        						}
        						String modid = tok[0] + ":" + tok[1];
        						BlockModel mod = models.get(modid);	// See if we have it
        						if (mod == null) {
        							mod = loadBlockModelFile(tok[0], tok[1]);
        							if (mod != null) {
        								models.put(modid, mod);
        							}
        						}
        						va.modelID = modid;	// save normalized ID
        					}
        				}
        			}
        		}
        	}
        }
        logger.info("Variant models loaded");
        // Now, resolve all parent references - load additional models
        LinkedList<BlockModel> modelToResolve = new LinkedList<BlockModel>(models.values());
        while (modelToResolve.isEmpty() == false) {
        	BlockModel mod = modelToResolve.pop();
        	if (mod.parent != null) {	// If parent reference
        		String modid = mod.parent;
        		if (modid.indexOf(':') < 0) {
        			modid = "minecraft:" + modid;
        		}
        		mod.parentModel = models.get(modid);	// Look up: see if already loaded
        		if (mod.parentModel == null) {
					String[] tok = modid.split(":");
					mod.parentModel = loadBlockModelFile(tok[0], tok[1]);
					if (mod.parentModel != null) {
						models.put(modid, mod.parentModel);
						modelToResolve.push(mod.parentModel);
						//logger.info("Loaded parent " + modid);
					}
        		}
        	}
        }
        logger.info("Parent models loaded and resolved");
        // Now resolve the elements for all the variants
        for (String blkname : blockRecords.keySet()) {
        	BlockRecord br = blockRecords.get(blkname);
        	if (br.sc != null) {
        		for (Entry<StateRec, List<VariantList>> var : br.varList.entrySet()) {
        		    // Produce merged element lists : for now, ignore random weights and just use first element of each section
        		    List<BlockElement> elems = new ArrayList<BlockElement>();
                    for (VariantList vl : var.getValue()) {
                        if (vl.variantList.size() > 0) {
                            Variant va = vl.variantList.get(0);
                            if(va.generateElements(models) == false) {
                                logger.warning(va.toString() + ": failed to generate elements for " + blkname + "[" + var.getKey() + "]");
                            }
                            else {
                                elems.addAll(va.elements);
                            }
                        }
                    }
                    // If single simple full cube
                    if ((elems.size() == 1) && (elems.get(0).isSimpleBlock())) {
                        if (br.handler != null) {
                            //logger.info(String.format("%s: %s is simple block with %s map",  blkname, var.getKey(), br.handler.getName()));
                            registerSimpleDynmapCubes(blkname, var.getKey(), elems.get(0), br.sc.getBlockType());
                        }
                    }
                    // Else if simple cuboid
                    else if (isSimpleCuboid(elems)) {
                        if (br.handler != null) {
                            //logger.info(String.format("%s: %s is simple block with %s map",  blkname, var.getKey(), br.handler.getName()));
                            registerSimpleDynmapCuboids(blkname, var.getKey(), elems, br.sc.getBlockType());
                        }
                    }
                    else  {
                        if (br.handler != null) {
                            //logger.info(String.format("%s: %s is simple block with %s map",  blkname, var.getKey(), br.handler.getName()));
                            registerDynmapPatches(blkname, var.getKey(), elems, br.sc.getBlockType());
                        }
                    }
        		}
        	}
        }
        logger.info("Elements generated");
        
        publishDynmapModData();
    }
    
    private ModSupportAPI dynmap_api;
    
    private static class ModDynmapRec {
    	ModTextureDefinition txtDef;
    	ModModelDefinition modDef;
    	Map<String, TextureFile> textureIDsByPath = new HashMap<String, TextureFile>();
    	int nextTxtID = 1;
    	
    	public TextureFile registerTexture(String txtpath) {
    	    txtpath = txtpath.toLowerCase();
    		TextureFile txtf = textureIDsByPath.get(txtpath);
    		if (txtf == null) {
    			String txtid = String.format("txt%04d", nextTxtID);
    			nextTxtID++;	// Assign next ID
    			// Split path to build full path
    			String[] ptok = txtpath.split(":");
    			String fname = "assets/" + ptok[0] + "/textures/" + ptok[1] + ".png";
    			txtf = txtDef.registerTextureFile(txtid, fname);
    			if (txtf != null) {
    				textureIDsByPath.put(txtpath, txtf);
    			}
    		}
    		return txtf;
    	}
        public TextureFile registerBiomeTexture(String txtpath) {
            TextureFile txtf = textureIDsByPath.get(txtpath);
            if (txtf == null) {
                String txtid = String.format("txt%04d", nextTxtID);
                nextTxtID++;    // Assign next ID
                // Split path to build full path
                String[] ptok = txtpath.split(":");
                String fname = "assets/" + ptok[0] + "/textures/" + ptok[1] + ".png";
                txtf = txtDef.registerBiomeTextureFile(txtid, fname);
                if (txtf != null) {
                    textureIDsByPath.put(txtpath, txtf);
                }
            }
            return txtf;
        }
        // Create block texture record
        public BlockTextureRecord getBlockTxtRec(String blknm, int[] meta) {
            BlockTextureRecord btr = txtDef.addBlockTextureRecord(blknm);
            if (btr == null) {
                return null;
            }
            DynmapBlockScanPlugin.logger.debug("Created block record for " + blknm + Arrays.toString(meta));
            // Set matching metadata
            for (int metaval : meta) {
                btr.setMetaValue(metaval);
            }
            return btr;
        }
        // Create cuboid model
        public CuboidBlockModel getCuboidModelRec(String blknm, int[] meta) {
            if (this.modDef == null) {
                this.modDef = this.txtDef.getModelDefinition();
            }
            CuboidBlockModel mod = this.modDef.addCuboidModel(blknm);
            DynmapBlockScanPlugin.logger.debug("Created cuboid model for " + blknm + Arrays.toString(meta));
            // Set matching metadata
            for (int metaval : meta) {
                mod.setMetaValue(metaval);
            }
            return mod;
        }
        // Create patch model
        public PatchBlockModel getPatchModelRec(String blknm, int[] meta) {
            if (this.modDef == null) {
                this.modDef = this.txtDef.getModelDefinition();
            }
            PatchBlockModel mod = this.modDef.addPatchModel(blknm);
            DynmapBlockScanPlugin.logger.debug("Created patch model for " + blknm + Arrays.toString(meta));
            // Set matching metadata
            for (int metaval : meta) {
                mod.setMetaValue(metaval);
            }
            return mod;
        }
    }
    private Map<String, ModDynmapRec> modTextureDef = new HashMap<String, ModDynmapRec>();
    
    private ModDynmapRec getModRec(String modid) {
        if (dynmap_api == null) {
            dynmap_api = ModSupportAPI.getAPI();
            if (dynmap_api == null) {
                return null;
            }
        }
        ModDynmapRec td = modTextureDef.get(modid);
        if (td == null) {
            td = new ModDynmapRec();
            td.txtDef = dynmap_api.getModTextureDefinition(modid, null);
            if (td.txtDef == null) {
                return null;
            }
            modTextureDef.put(modid, td);
            logger.debug("Create dynmap mod record for " + modid);
        }
        return td;
    }

        
    public void registerSimpleDynmapCubes(String blkname, StateRec state, BlockElement element, WellKnownBlockClasses type) {
    	String[] tok = blkname.split(":");
    	String modid = tok[0];
    	String blknm = tok[1];
    	int[] meta = state.metadata;
    	if (tok[0].equals("minecraft")) {	// Skip vanilla
    		return;
    	}
    	// Get record for mod
    	ModDynmapRec td = getModRec(modid);
    	// Create block texture record
    	BlockTextureRecord btr = td.getBlockTxtRec(blknm, meta);
    	if (btr == null) {
    		return;
    	}
    	boolean tinting = false;   // Watch out for tinting
        for (BlockFace f : element.faces.values()) {
            if (f.tintindex >= 0) {
                tinting = true;
                break;
            }
        }
        // If block has tinting, try to figure out what to use
        if (tinting) {
            String txtfile = null;
            BlockTintOverride ovr = overrides.getTinting(modid, blknm, state.getProperties());
            if (ovr == null) { // No match, need to guess
                switch (type) {
                    case LEAVES:
                    case VINES:
                        txtfile = "minecraft:colormap/foliage";
                        break;
                    default:
                        txtfile = "minecraft:colormap/grass";
                        break;
                }
            }
            else {
                txtfile = ovr.colormap[0];
            }
            if (txtfile != null) {
                TextureFile gtf = td.registerBiomeTexture(txtfile);
                btr.setBlockColorMapTexture(gtf);
            }
        }
       
    	// Loop over the images for the element
    	for (Entry<EnumFacing, BlockFace> face : element.faces.entrySet()) {
    	    EnumFacing facing = face.getKey();
    		BlockFace f = face.getValue();
    		BlockSide bs = faceToSide.get(facing);
    		if ((bs != null) && (f.texture != null)) {
    			TextureFile gtf = td.registerTexture(f.texture);
				int faceidx = (360-f.rotation);
				if (!element.uvlock) {
				    faceidx = faceidx + f.facerotation;
				}
                TextureModifier tm = TextureModifier.NONE;
			    switch (faceidx % 360) {
					case 90:
						tm = TextureModifier.ROT90;
						break;
					case 180:
						tm = TextureModifier.ROT180;
						break;
					case 270:
						tm = TextureModifier.ROT270;
						break;
				}
				btr.setSideTexture(gtf, tm, bs);
    		}
    	}
    }

    public boolean isSimpleCuboid(List<BlockElement> elements) {
        for (BlockElement be : elements) {
            if (be.isSimpleCuboid() == false) {
                return false;
            }
        }
        return true;
    }

    public void registerSimpleDynmapCuboids(String blkname, StateRec state, List<BlockElement> elems, WellKnownBlockClasses type) {
        String[] tok = blkname.split(":");
        String modid = tok[0];
        String blknm = tok[1];
        int[] meta = state.metadata;
        if (tok[0].equals("minecraft")) {   // Skip vanilla
            return;
        }
        // Get record for mod
        ModDynmapRec td = getModRec(modid);
        // Create block texture record
        BlockTextureRecord btr = td.getBlockTxtRec(blknm, meta);
        if (btr == null) {
            return;
        }
        // Check for tinting and/or culling
        boolean tinting = false;   // Watch out for tinting
        boolean culling = false;
        for (BlockElement be : elems) {
            for (BlockFace f : be.faces.values()) {
                if (f.tintindex >= 0) {
                    tinting = true;
                    break;
                }
                if (f.cullface != null) {
                    culling = true;
                }
            }
        }
        // Set to transparent (or semitransparent if culling)
        if (culling) {
            btr.setTransparencyMode(TransparencyMode.SEMITRANSPARENT);
        }
        else {
            btr.setTransparencyMode(TransparencyMode.TRANSPARENT);
        }
        // If block has tinting, try to figure out what to use
        if (tinting) {
            String txtfile = null;
            BlockTintOverride ovr = overrides.getTinting(modid, blknm, state.getProperties());
            if (ovr == null) { // No match, need to guess
                switch (type) {
                case LEAVES:
                case VINES:
                    txtfile = "minecraft:colormap/foliage";
                    break;
                default:
                    txtfile = "minecraft:colormap/grass";
                    break;
                }
            }
            else {
                txtfile = ovr.colormap[0];
            }
            if (txtfile != null) {
                TextureFile gtf = td.registerBiomeTexture(txtfile);
                btr.setBlockColorMapTexture(gtf);
            }
        }
        // Get cuboid model
        CuboidBlockModel mod = td.getCuboidModelRec(blknm, meta);
        // Loop over elements
        int imgidx = 0;
        int[] cuboididx = new int[6];
        for (BlockElement be : elems) {
            // Initialize to no texture for each side
            for (int v = 0; v < cuboididx.length; v++) cuboididx[v] = -1;
            // Loop over the images for the element
            for (Entry<EnumFacing, BlockFace> face : be.faces.entrySet()) {
                EnumFacing facing = face.getKey();
                BlockFace f = face.getValue();
                if (f.texture != null) {
                    TextureFile gtf = td.registerTexture(f.texture);
                    int faceidx = (360-f.rotation);
                    if (!be.uvlock) {
                        faceidx = faceidx + f.facerotation;
                    }
                    TextureModifier tm = TextureModifier.NONE;
                    switch (faceidx % 360) {
                    case 90:
                        tm = TextureModifier.ROT90;
                        break;
                    case 180:
                        tm = TextureModifier.ROT180;
                        break;
                    case 270:
                        tm = TextureModifier.ROT270;
                        break;
                    }
                    cuboididx[facing.getIndex()] = imgidx;
                    btr.setPatchTexture(gtf, tm, imgidx);
                    imgidx++;
                }
            }
            // Add cuboid element to model
            mod.addCuboid(be.from[0]/16.0, be.from[1]/16.0, be.from[2]/16.0, be.to[0]/16.0, be.to[1]/16.0, be.to[2]/16.0, cuboididx);
        }
    }

    public void registerDynmapPatches(String blkname, StateRec state, List<BlockElement> elems, WellKnownBlockClasses type) {
        String[] tok = blkname.split(":");
        String modid = tok[0];
        String blknm = tok[1];
        int[] meta = state.metadata;
        if (tok[0].equals("minecraft")) {   // Skip vanilla
            return;
        }
        // Get record for mod
        ModDynmapRec td = getModRec(modid);
        // Create block texture record
        BlockTextureRecord btr = td.getBlockTxtRec(blknm, meta);
        if (btr == null) {
            return;
        }
        // Check for tinting and/or culling
        boolean tinting = false;   // Watch out for tinting
        boolean culling = false;
        for (BlockElement be : elems) {
            for (BlockFace f : be.faces.values()) {
                if (f.tintindex >= 0) {
                    tinting = true;
                    break;
                }
                if (f.cullface != null) {
                    culling = true;
                }
            }
        }
        // Set to transparent (or semitransparent if culling)
        if (culling) {
            btr.setTransparencyMode(TransparencyMode.SEMITRANSPARENT);
        }
        else {
            btr.setTransparencyMode(TransparencyMode.TRANSPARENT);
        }
        // If block has tinting, try to figure out what to use
        if (tinting) {
            String txtfile = null;
            BlockTintOverride ovr = overrides.getTinting(modid, blknm, state.getProperties());
            if (ovr == null) { // No match, need to guess
                switch (type) {
                case LEAVES:
                case VINES:
                    txtfile = "minecraft:colormap/foliage";
                    break;
                default:
                    txtfile = "minecraft:colormap/grass";
                    break;
                }
            }
            else {
                txtfile = ovr.colormap[0];
            }
            if (txtfile != null) {
                TextureFile gtf = td.registerBiomeTexture(txtfile);
                btr.setBlockColorMapTexture(gtf);
            }
        }
        // Get patch model
        PatchBlockModel mod = td.getPatchModelRec(blknm, meta);
        // Loop over elements
        int patchidx = 0;
        for (BlockElement be : elems) {
            // Loop over the images for the element
            for (Entry<EnumFacing, BlockFace> face : be.faces.entrySet()) {
                EnumFacing facing = face.getKey();
                BlockFace f = face.getValue();
                BlockSide bs = faceToSide.get(facing);
                if ((bs != null) && (f.texture != null)) {
                    TextureFile gtf = td.registerTexture(f.texture);
                    int faceidx = (360-f.rotation);
                    if (!be.uvlock) {
                        faceidx = faceidx + f.facerotation;
                    }
                    TextureModifier tm = TextureModifier.NONE;
                    switch (faceidx % 360) {
                    case 90:
                        tm = TextureModifier.ROT90;
                        break;
                    case 180:
                        tm = TextureModifier.ROT180;
                        break;
                    case 270:
                        tm = TextureModifier.ROT270;
                        break;
                    }
                    // And add patch to to model
                    if (addPatch(mod, facing, be) != null) {
                        btr.setPatchTexture(gtf, tm, patchidx);
                        // Increment patch count
                        patchidx++;
                    }
                    else {
                        logger.info("Failed to add patch for " + blkname);
                    }
                }
            }
        }
    }
    
    private String addPatch(PatchBlockModel mod, EnumFacing facing, BlockElement be) {
        // First, do the rotation on the from/to
        Vector3D fromvec = new Vector3D(be.from[0], be.from[1], be.from[2]);
        Vector3D tovec = new Vector3D(be.to[0], be.to[1], be.to[2]);
        Vector3D originvec = new Vector3D();
        Vector3D uvec = new Vector3D();
        Vector3D vvec = new Vector3D();
        // Now, compute corner vectors, based on which side
        switch (facing) {
            case DOWN:
                originvec.x = fromvec.x; originvec.y = fromvec.y; originvec.z = fromvec.z;
                uvec.x = tovec.x; uvec.y = fromvec.y; uvec.z = fromvec.z;
                vvec.x = fromvec.x; vvec.y = fromvec.y; vvec.z = tovec.z;
                break;
            case UP:
                originvec.x = fromvec.x; originvec.y = tovec.y; originvec.z = tovec.z;
                uvec.x = tovec.x; uvec.y = tovec.y; uvec.z = tovec.z;
                vvec.x = fromvec.x; vvec.y = tovec.y; vvec.z = fromvec.z;
                break;
            case WEST:
                originvec.x = fromvec.x; originvec.y = fromvec.y; originvec.z = fromvec.z;
                uvec.x = fromvec.x; uvec.y = fromvec.y; uvec.z = tovec.z;
                vvec.x = fromvec.x; vvec.y = tovec.y; vvec.z = fromvec.z;
                break;
            case EAST:
                originvec.x = tovec.x; originvec.y = fromvec.y; originvec.z = tovec.z;
                uvec.x = tovec.x; uvec.y = fromvec.y; uvec.z = fromvec.z;
                vvec.x = tovec.x; vvec.y = tovec.y; vvec.z = tovec.z;
                break;
            case NORTH:
                originvec.x = tovec.x; originvec.y = fromvec.y; originvec.z = fromvec.z;
                uvec.x = fromvec.x; uvec.y = fromvec.y; uvec.z = fromvec.z;
                vvec.x = tovec.x; vvec.y = tovec.y; vvec.z = fromvec.z;
                break;
            case SOUTH:
                originvec.x = fromvec.x; originvec.y = fromvec.y; originvec.z = tovec.z;
                uvec.x = tovec.x; uvec.y = fromvec.y; uvec.z = tovec.z;
                vvec.x = fromvec.x; vvec.y = tovec.y; vvec.z = tovec.z;
                break;
        }
        if ((be.rotation != null) && (be.rotation.angle != 0)) {
            Matrix3D rot = new Matrix3D();
            Vector3D scale = new Vector3D(1, 1, 1);
            double rescale = (1.0 / Math.cos(Math.toRadians(be.rotation.angle))) - 1.0;
            if ("z".equals(be.rotation.axis)) {
                rot.rotateXY(be.rotation.angle);
                scale.x += rescale;
                scale.y += rescale;
            }
            else if ("x".equals(be.rotation.axis)) {
                rot.rotateYZ(be.rotation.angle);
                scale.y += rescale;
                scale.z += rescale;
            }
            else {
                rot.rotateXZ(be.rotation.angle);
                scale.x += rescale;
                scale.z += rescale;
            }
            Vector3D axis;
            if (be.rotation.origin != null) {
                axis = new Vector3D(be.rotation.origin[0], be.rotation.origin[1], be.rotation.origin[2]);
            }
            else {
                axis = new Vector3D(8, 8, 8);
            }
            // Now do rotation
            originvec.subtract(axis);
            uvec.subtract(axis);
            vvec.subtract(axis);
            rot.transform(originvec);
            rot.transform(uvec);
            rot.transform(vvec);
            if (be.rotation.rescale) {
                originvec.scale(scale);
                uvec.scale(scale);
                vvec.scale(scale);
            }
            originvec.add(axis);
            uvec.add(axis);
            vvec.add(axis);
        }
        // Now unit scale
        originvec.scale(1.0/16.0);
        uvec.scale(1.0/16.0);
        vvec.scale(1.0/16.0);

        // Now, add patch, based on facing
        return mod.addPatch(originvec.x, originvec.y, originvec.z, uvec.x, uvec.y, uvec.z, vvec.x, vvec.y, vvec.z, SideVisible.TOP);
    }

    
    public void publishDynmapModData() {
    	for (ModDynmapRec mod : modTextureDef.values()) {
    		if (mod.txtDef != null) {
    			mod.txtDef.publishDefinition();
    			logger.info("Published " + mod.txtDef.getModID() + " textures to Dynmap");
    		}
    		if (mod.modDef != null) {
                mod.modDef.publishDefinition();
                logger.info("Published " + mod.modDef.getModID() + " models to Dynmap");
            }
    	}
    
    }
    
    public static InputStream openResource(String modid, String rname) {
        String rname_lc = rname.toLowerCase();
        if (modid != null) {
            ModContainer mc = Loader.instance().getIndexedModList().get(modid);
            Object mod = (mc != null)?mc.getMod():null;
            if (mod != null) {
                InputStream is = mod.getClass().getClassLoader().getResourceAsStream(rname);
                if (is == null) {
                    is = mod.getClass().getClassLoader().getResourceAsStream(rname_lc);
                }
                if (is != null) {
                    return is;
                }
            }
        }
        List<ModContainer> mcl = Loader.instance().getModList();
        for (ModContainer mc : mcl) {
            Object mod = mc.getMod();
            if (mod == null) continue;
            InputStream is = null;
            try {
                is = mod.getClass().getClassLoader().getResourceAsStream(rname);
                if (is == null) {
                    is = mod.getClass().getClassLoader().getResourceAsStream(rname_lc);
                }
            } catch (Error x) {
                logger.info("Error loading " + rname + " from " + mc.getModId());
            }
            if (is != null) {
                return is;
            }
        }
        return null;
    }
    
    public Map<String, List<String>> buildPropoertMap(BlockStateContainer bsc) {
    	Map<String, List<String>> renderProperties = new HashMap<String, List<String>>();
		// Build table of render properties and valid values
		for (IProperty<?> p : bsc.getProperties()) {
			String pn = p.getName();
			ArrayList<String> pvals = new ArrayList<String>();
			for (Comparable<?> val : p.getAllowedValues()) {
				if (val instanceof IStringSerializable) {
					pvals.add(((IStringSerializable)val).getName());
				}
				else {
					pvals.add(val.toString());
				}
			}
			renderProperties.put(pn, pvals);
		}
		return renderProperties;
    }
    
    // Build ImmutableMap<String, String> from properties in IBlockState
    public ImmutableMap<String, String> fromIBlockState(IBlockState bs) {
    	ImmutableMap.Builder<String,String> bld = ImmutableMap.builder();
    	for (Entry<IProperty<?>, Comparable<?>> x : bs.getProperties().entrySet()) {
    		Comparable<?> v = x.getValue();
    		if (v instanceof IStringSerializable) {
    			bld.put(x.getKey().getName(), ((IStringSerializable)v).getName());
    		}
    		else {
    			bld.put(x.getKey().getName(), v.toString());
    		}
    	}
    	return bld.build();
    }
    
    private static BlockState loadBlockState(String modid, String respath, BlockStateOverrides override, Map<String, List<String>> propMap) {
    	BlockStateOverride ovr = override.getOverride(modid, respath);
    	
    	if (ovr == null) {	// No override
    		return loadBlockStateFile(modid, respath);
    	}
    	else if (ovr.blockStateName != null) {	// Simple override
    		return loadBlockStateFile(modid, ovr.blockStateName);
    	}
    	else if (ovr.baseNameProperty != null) {	// MUltiple files based on base property
    		List<String> vals = propMap.get(ovr.baseNameProperty);	// Look up defned values
    		if (vals == null) {
    			Log.error(String.format("%s:%s : bad baseNameProperty=%s",  modid, respath, ovr.baseNameProperty));;
    			return null;
    		}
    		BlockState bs = new BlockState();
    		bs.nestedProp = ovr.baseNameProperty;
    		bs.nestedValueMap = new HashMap<String, BlockState>();
    		for (String v : vals) {
    			BlockState bs2 = loadBlockStateFile(modid, v + ovr.nameSuffix);
    			if (bs2 != null) {
    				bs.nestedValueMap.put(v,  bs2);
    			}
    		}
    		return bs;
    	}

		return null;
    }
    
    private static BlockState loadBlockStateFile(String modid, String respath) {
        String path = "assets/" + modid + "/blockstates/" + respath + ".json";
    	BlockState bs = null;
        InputStream is = openResource(modid, path);
        if (is != null) {	// Found it?
        	Reader rdr = new InputStreamReader(is, Charsets.UTF_8);
        	Gson parse = BlockState.buildParser();	// Get parser
        	try {
                JsonReader jrdr = new JsonReader(rdr);
                jrdr.setLenient(true);
        	    bs = parse.fromJson(jrdr, BlockState.class);
        	} catch (JsonSyntaxException jsx) {
                logger.warning(String.format("%s:%s : JSON syntax error in block state file", modid, path), jsx);
        	}
        	try {
        	    is.close();
			} catch (IOException e) {
			}
        	if (bs == null) {
        		logger.info(String.format("%s:%s : Failed to load blockstate!", modid, path));
        	}
        }
        else {
    		logger.info(String.format("%s:%s : Failed to open blockstate", modid, path));
        }
        return bs;
    }
    
    private static BlockModel loadBlockModelFile(String modid, String respath) {
        String path = "assets/" + modid + "/models/" + respath + ".json";
    	BlockModel bs = null;
        InputStream is = openResource(modid, path);
        if (is != null) {	// Found it?
        	Reader rdr = new InputStreamReader(is, Charsets.UTF_8);
        	Gson parse = BlockModel.buildParser();	// Get parser
        	try {
        	    JsonReader jrdr = new JsonReader(rdr);
        	    jrdr.setLenient(true);
        	    bs = parse.fromJson(jrdr, BlockModel.class);
        	} catch (JsonSyntaxException jsx) {
                logger.warning(String.format("%s:%s : JSON syntax error in model file", modid, path), jsx);
        	}
        	try {
				is.close();
			} catch (IOException e) {
			}
        	if (bs == null) {
        		logger.info(String.format("%s:%s : Failed to load model!", modid, path));
        	}
        }
        else {
    		logger.info(String.format("%s:%s : Failed to open model", modid, path));
        }
        return bs;
    }
    
    public static class OurLog {
        Logger log;
        public static final String DM = "[DynmapBlockScan] ";
        OurLog() {
            log = LogManager.getLogger("DynmapBlockScan");
        }
        public void debug(String s) {
            log.debug(DM + s);
        }
        public void info(String s) {
            log.info(DM + s);
        }
        public void severe(Throwable t) {
            log.fatal(t);
        }
        public void severe(String s) {
            log.fatal(DM + s);
        }
        public void severe(String s, Throwable t) {
            log.fatal(DM + s, t);
        }
        public void verboseinfo(String s) {
            log.info(DM + s);
        }
        public void warning(String s) {
            log.warn(DM + s);
        }
        public void warning(String s, Throwable t) {
            log.warn(DM + s, t);
        }
    }
}

