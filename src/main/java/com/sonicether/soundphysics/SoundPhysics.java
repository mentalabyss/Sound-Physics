package com.sonicether.soundphysics;

import java.util.regex.Pattern;
import java.util.List;
import java.util.ArrayList;
import java.util.ListIterator;
import java.util.Collections;
import java.nio.FloatBuffer;

import org.lwjgl.openal.AL10;
import org.lwjgl.openal.AL11;
import org.lwjgl.openal.ALC10;
import org.lwjgl.openal.ALCcontext;
import org.lwjgl.openal.ALCdevice;
import org.lwjgl.openal.EFX10;
import org.lwjgl.BufferUtils;

import net.minecraft.block.Block;
import net.minecraft.block.SoundType;
import net.minecraft.block.material.Material;
import net.minecraft.block.properties.PropertyDirection;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.client.Minecraft;
import net.minecraft.entity.Entity;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.SoundCategory;
import net.minecraft.util.SoundEvent;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.RayTraceResult;
import net.minecraft.util.math.Vec3d;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.common.event.FMLInitializationEvent;
import net.minecraftforge.fml.common.event.FMLPreInitializationEvent;
import net.minecraftforge.client.event.RenderGameOverlayEvent;
import net.minecraftforge.client.event.RenderGameOverlayEvent.Text;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import paulscode.sound.SoundSystemConfig;
import paulscode.sound.SoundSystem;
import paulscode.sound.CommandObject;
import paulscode.sound.SoundBuffer;
import javax.sound.sampled.AudioFormat;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Timer;  
import java.util.TimerTask;

import org.apache.logging.log4j.Logger;
import org.apache.logging.log4j.LogManager;

@Mod(modid = SoundPhysics.modid, clientSideOnly = true, acceptedMinecraftVersions = SoundPhysics.mcVersion, version = SoundPhysics.version, guiFactory = "com.sonicether.soundphysics.SPGuiFactory",
	dependencies = SoundPhysics.deps)
public class SoundPhysics {

	public static final String modid = "soundphysics";
	public static final String version = "1.0.8-1";
	public static final String mcVersion = "1.12.2";
	public static final String deps = "";

	public static final Logger logger = LogManager.getLogger(modid);

	public static boolean onServer = false;

	private static final Pattern rainPattern = Pattern.compile(".*rain.*");
	private static final Pattern stepPattern = Pattern.compile(".*step.*");
	private static final Pattern blockPattern = Pattern.compile(".*block.*");
	private static final Pattern uiPattern = Pattern.compile(".*\\/ui\\/.*");
	private static final Pattern clickPattern = Pattern.compile(".*random.click.*");
	private static final Pattern noteBlockPattern = Pattern.compile(".*block.note.*");

	@Mod.EventHandler
	public void preInit(final FMLPreInitializationEvent event) {
		Config.instance.preInit(event);
	}

	@Mod.EventHandler
	public void init(final FMLInitializationEvent event) {
		Config.instance.init(event);
	}

	private static int auxFXSlot0;
	private static int auxFXSlot1;
	private static int auxFXSlot2;
	private static int auxFXSlot3;
	private static int reverb0;
	private static int reverb1;
	private static int reverb2;
	private static int reverb3;
	private static int directFilter0;
	private static int sendFilter0;
	private static int sendFilter1;
	private static int sendFilter2;
	private static int sendFilter3;

	private static Minecraft mc;
	private static SoundSystem sndSystem;

	private static SoundCategory lastSoundCategory;
	private static String lastSoundName;

	private static ProcThread proc_thread;
	private static volatile boolean thread_alive;
	private static volatile boolean thread_signal_death;
	private static volatile List<Source> source_list;

	// THESE VARIABLES ARE CONSTANTLY ACCESSED AND USED BY ASM INJECTED CODE! DO
	// NOT REMOVE!
	public static int attenuationModel = SoundSystemConfig.ATTENUATION_ROLLOFF;
	public static float globalRolloffFactor = Config.rolloffFactor;
	public static float globalVolumeMultiplier = 4.0f;
	public static float globalReverbMultiplier = 0.7f * Config.globalReverbGain;
	public static double soundDistanceAllowance = Config.soundDistanceAllowance;

	/**
	 * CALLED BY ASM INJECTED CODE!
	 */
	public static void init(SoundSystem snds) {
		mc = Minecraft.getMinecraft();
		sndSystem = snds;
		try {
			/*if (Config.dopplerEnabled) {
				sndSystem.changeDopplerFactor(1.0f);
				AL11.alSpeedOfSound(343.3f); // Should already be 343.3 but just in case
			}*/
			setupEFX();
			setupThread();
		} catch (Throwable e) {
			logError("Failed to init EFX or thread");
			logError(e.toString());
		}
	}

	public static class Source {
		public int sourceID;
		public float posX;
		public float posY;
		public float posZ;
		public SoundCategory category;
		public String name;
		public int frequency;
		public int size;
		public int bufferID;

		public Source(int sid,float px,float py,float pz,SoundCategory cat,String n) {
			this.sourceID = sid;
			this.posX = px;
			this.posY = py;
			this.posZ = pz;
			this.category = cat;
			this.name = n;
			bufferID = AL10.alGetSourcei(sid, AL10.AL_BUFFER);
			size = AL10.alGetBufferi(bufferID, AL10.AL_SIZE);
			frequency = AL10.alGetBufferi(bufferID, AL10.AL_FREQUENCY);
		}
	}

	public static class ProcThread extends Thread {
		@Override
		public synchronized void run() {
			while (thread_alive) {
				while (!Config.dynamicEnvironementEvalutaion) {
					try {
						Thread.sleep(1000);
					} catch (Exception e) {
						logError(String.valueOf(e));
					}
				}
				synchronized (source_list) {
					//log("Updating env " + String.valueOf(source_list.size()));
					ListIterator<Source> iter = source_list.listIterator();
					while (iter.hasNext()) {
						Source source = iter.next();
						//log("Updating sound '" + source.name + "' SourceID:" + String.valueOf(source.sourceID));
						//boolean pl = sndHandler.isSoundPlaying(source.sound);
						//FloatBuffer pos = BufferUtils.createFloatBuffer(3);
						//AL10.alGetSource(source.sourceID,AL10.AL_POSITION,pos);
						//To try ^
						int state = AL10.alGetSourcei(source.sourceID, AL10.AL_SOURCE_STATE);
						//int byteoff = AL10.alGetSourcei(source.sourceID, AL11.AL_BYTE_OFFSET);
						//boolean finished = source.size == byteoff;
						if (state == AL10.AL_PLAYING) {
							FloatBuffer pos = BufferUtils.createFloatBuffer(3);
							AL10.alGetSource(source.sourceID,AL10.AL_POSITION,pos);
							source.posX = pos.get(0);
							source.posY = pos.get(1);
							source.posZ = pos.get(2);
							evaluateEnvironment(source.sourceID,source.posX,source.posY,source.posZ,source.category,source.name);
						} else /*if (state == AL10.AL_STOPPED)*/ {
							iter.remove();
						}
					}
				}
				try {
					Thread.sleep(1000/Config.dynamicEnvironementEvalutaionFrequency);
				} catch (Exception e) {
					logError(String.valueOf(e));
				}
			}
			thread_signal_death = true;
		}
	}

	public static void source_check_add(Source s) {
		synchronized (source_list) {
			ListIterator<Source> iter = source_list.listIterator();
			while (iter.hasNext()) {
				Source sn = iter.next();
				if (sn.sourceID == s.sourceID) {
					sn.posX = s.posX;
					sn.posY = s.posY;
					sn.posZ = s.posZ;
					sn.category = s.category;
					sn.name = s.name;
					return;
				}
			}
			source_list.add(s);
		}
	}

	@Mod.EventBusSubscriber
	public static class DebugDisplayEventHandler {
		@SubscribeEvent
		public static void onDebugOverlay(RenderGameOverlayEvent.Text event) {
			if (mc != null && mc.gameSettings.showDebugInfo && Config.dynamicEnvironementEvalutaion && Config.debugInfoShow) {
				event.getLeft().add("");
				event.getLeft().add("[SoundPhysics] "+String.valueOf(source_list.size())+" Sources");
				event.getLeft().add("[SoundPhysics] Source list :");
				synchronized (source_list) {
					ListIterator<Source> iter = source_list.listIterator();
					while (iter.hasNext())  {
						Source s = iter.next();
						Vec3d tmp = new Vec3d(s.posX,s.posY,s.posZ);
						event.getLeft().add(String.valueOf(s.sourceID)+"-"+s.category.toString()+"-"+s.name+"-"+tmp.toString());
						/*int buffq = AL10.alGetSourcei(s.sourceID, AL10.AL_BUFFERS_QUEUED);
						int buffp = AL10.alGetSourcei(s.sourceID, AL10.AL_BUFFERS_PROCESSED);
						int sampoff = AL10.alGetSourcei(s.sourceID, AL11.AL_SAMPLE_OFFSET);
						int byteoff = AL10.alGetSourcei(s.sourceID, AL11.AL_BYTE_OFFSET);
						String k = "";
						if (sampoff!=0) {
							//k = String.valueOf(sampoff)+"/"+String.valueOf((byteoff/sampoff)*size)+" ";
							k = String.valueOf((float)sampoff/(float)s.frequency)+"/"+String.valueOf((float)((byteoff/sampoff)*s.size)/(float)s.frequency)+" ";
						} else {
							k = "0/? ";
						}
						event.getLeft().add(k+String.valueOf(buffp)+"/"+String.valueOf(buffq)+" "+String.valueOf(s.bufferID));
						event.getLeft().add("----");*/
					}
				}
			}
		}
	}

	private static synchronized void setupThread() {
		if (source_list == null) source_list = Collections.synchronizedList(new ArrayList<Source>());
		else source_list.clear();

		/*if (proc_thread != null) {
			thread_signal_death = false;
			thread_alive = false;
			while (!thread_signal_death);
		}*/
		if (proc_thread == null) {
			proc_thread = new ProcThread();
			thread_alive = true;
			proc_thread.start();
		}
	}

	public static void applyConfigChanges() {
		globalRolloffFactor = Config.rolloffFactor;
		globalReverbMultiplier = 0.7f * Config.globalReverbGain;
		soundDistanceAllowance = Config.soundDistanceAllowance;

		if (auxFXSlot0 != 0) {
			// Set the global reverb parameters and apply them to the effect and
			// effectslot
			setReverbParams(ReverbParams.getReverb0(), auxFXSlot0, reverb0);
			setReverbParams(ReverbParams.getReverb1(), auxFXSlot1, reverb1);
			setReverbParams(ReverbParams.getReverb2(), auxFXSlot2, reverb2);
			setReverbParams(ReverbParams.getReverb3(), auxFXSlot3, reverb3);
		}
	}

	private static void setupEFX() {
		// Get current context and device
		final ALCcontext currentContext = ALC10.alcGetCurrentContext();
		final ALCdevice currentDevice = ALC10.alcGetContextsDevice(currentContext);

		if (ALC10.alcIsExtensionPresent(currentDevice, "ALC_EXT_EFX")) {
			log("EFX Extension recognized.");
		} else {
			logError("EFX Extension not found on current device. Aborting.");
			return;
		}

		// Create auxiliary effect slots
		auxFXSlot0 = EFX10.alGenAuxiliaryEffectSlots();
		log("Aux slot " + auxFXSlot0 + " created");
		EFX10.alAuxiliaryEffectSloti(auxFXSlot0, EFX10.AL_EFFECTSLOT_AUXILIARY_SEND_AUTO, AL10.AL_TRUE);

		auxFXSlot1 = EFX10.alGenAuxiliaryEffectSlots();
		log("Aux slot " + auxFXSlot1 + " created");
		EFX10.alAuxiliaryEffectSloti(auxFXSlot1, EFX10.AL_EFFECTSLOT_AUXILIARY_SEND_AUTO, AL10.AL_TRUE);

		auxFXSlot2 = EFX10.alGenAuxiliaryEffectSlots();
		log("Aux slot " + auxFXSlot2 + " created");
		EFX10.alAuxiliaryEffectSloti(auxFXSlot2, EFX10.AL_EFFECTSLOT_AUXILIARY_SEND_AUTO, AL10.AL_TRUE);

		auxFXSlot3 = EFX10.alGenAuxiliaryEffectSlots();
		log("Aux slot " + auxFXSlot3 + " created");
		EFX10.alAuxiliaryEffectSloti(auxFXSlot3, EFX10.AL_EFFECTSLOT_AUXILIARY_SEND_AUTO, AL10.AL_TRUE);
		checkErrorLog("Failed creating auxiliary effect slots!");

		reverb0 = EFX10.alGenEffects();
		EFX10.alEffecti(reverb0, EFX10.AL_EFFECT_TYPE, EFX10.AL_EFFECT_EAXREVERB);
		checkErrorLog("Failed creating reverb effect slot 0!");
		reverb1 = EFX10.alGenEffects();
		EFX10.alEffecti(reverb1, EFX10.AL_EFFECT_TYPE, EFX10.AL_EFFECT_EAXREVERB);
		checkErrorLog("Failed creating reverb effect slot 1!");
		reverb2 = EFX10.alGenEffects();
		EFX10.alEffecti(reverb2, EFX10.AL_EFFECT_TYPE, EFX10.AL_EFFECT_EAXREVERB);
		checkErrorLog("Failed creating reverb effect slot 2!");
		reverb3 = EFX10.alGenEffects();
		EFX10.alEffecti(reverb3, EFX10.AL_EFFECT_TYPE, EFX10.AL_EFFECT_EAXREVERB);
		checkErrorLog("Failed creating reverb effect slot 3!");

		// Create filters
		directFilter0 = EFX10.alGenFilters();
		EFX10.alFilteri(directFilter0, EFX10.AL_FILTER_TYPE, EFX10.AL_FILTER_LOWPASS);

		sendFilter0 = EFX10.alGenFilters();
		EFX10.alFilteri(sendFilter0, EFX10.AL_FILTER_TYPE, EFX10.AL_FILTER_LOWPASS);

		sendFilter1 = EFX10.alGenFilters();
		EFX10.alFilteri(sendFilter1, EFX10.AL_FILTER_TYPE, EFX10.AL_FILTER_LOWPASS);

		sendFilter2 = EFX10.alGenFilters();
		EFX10.alFilteri(sendFilter2, EFX10.AL_FILTER_TYPE, EFX10.AL_FILTER_LOWPASS);

		sendFilter3 = EFX10.alGenFilters();
		EFX10.alFilteri(sendFilter3, EFX10.AL_FILTER_TYPE, EFX10.AL_FILTER_LOWPASS);
		checkErrorLog("Error creating lowpass filters!");

		applyConfigChanges();
	}

	/**
	 * CALLED BY ASM INJECTED CODE!
	 */
	public static void setLastSoundCategory(final SoundCategory sc) {
		lastSoundCategory = sc;
	}

	/**
	 * CALLED BY ASM INJECTED CODE!
	 */
	public static void setLastSoundName(final String soundName, final String eventName) {
		lastSoundName = eventName+"|"+soundName.split(":")[1]; // Quick and dirty hack to check the event and sound name
	}

	/**
	 * CALLED BY ASM INJECTED CODE!
	 */
	public static void setLastSoundName(final String soundName) {
		lastSoundName = soundName;
	}

	/**
	 * CALLED BY ASM INJECTED CODE!
	 */
	// For sounds that get played normally
	public static void onPlaySound(final float posX, final float posY, final float posZ, final int sourceID) {
		onPlaySound(posX, posY, posZ, sourceID, lastSoundCategory, lastSoundName);
	}

	/**
	 * CALLED BY ASM INJECTED CODE!
	 */
	// For sounds that get played using OpenAL directly or just not using the minecraft sound system
	public static void onPlaySoundAL(final float posX, final float posY, final float posZ, final int sourceID) {
		onPlaySound(posX, posY, posZ, sourceID, SoundCategory.MASTER, "openal");
	}

	/**
	 * CALLED BY ASM INJECTED CODE!
	 */
	public static void onPlaySound(final float posX, final float posY, final float posZ, final int sourceID, SoundCategory soundCat, String soundName) {
		//log(String.valueOf(posX)+" "+String.valueOf(posY)+" "+String.valueOf(posZ)+" - "+String.valueOf(sourceID)+" - "+soundCat.toString()+" - "+soundName);
		if (Config.noteBlockEnable && soundCat == SoundCategory.RECORDS && noteBlockPattern.matcher(soundName).matches()) soundCat = SoundCategory.BLOCKS;
		evaluateEnvironment(sourceID, posX, posY, posZ,soundCat,soundName);
		if (!Config.dynamicEnvironementEvalutaion) return;
		if ((mc.player == null || mc.world == null || posY <= 0 || soundCat == SoundCategory.RECORDS 
		|| soundCat == SoundCategory.MUSIC) || (Config.skipRainOcclusionTracing && rainPattern.matcher(soundName).matches())) return;
		if (clickPattern.matcher(soundName).matches() || uiPattern.matcher(soundName).matches()) return;
		Source tmp = new Source(sourceID,posX,posY,posZ,soundCat,soundName);
		source_check_add(tmp);
	}

	/**
	 * CALLED BY ASM INJECTED CODE!
	 */
	public static SoundBuffer onLoadSound(SoundBuffer buff, String filename) {
		if (buff == null || buff.audioFormat.getChannels() == 1 || !Config.autoSteroDownmix) return buff;
		if (mc.player == null || mc.world == null || lastSoundCategory == SoundCategory.RECORDS
		| lastSoundCategory == SoundCategory.MUSIC || uiPattern.matcher(filename).matches() || clickPattern.matcher(filename).matches()) {
			if (Config.autoSteroDownmixLogging) log("Not converting sound '"+filename+"'("+buff.audioFormat.toString()+")");
			return buff;
		}
		AudioFormat orignalformat = buff.audioFormat;
		int bits = orignalformat.getSampleSizeInBits();
		boolean bigendian = orignalformat.isBigEndian();
		AudioFormat monoformat = new AudioFormat(orignalformat.getEncoding(), orignalformat.getSampleRate(), bits,
												1, orignalformat.getFrameSize(), orignalformat.getFrameRate(), bigendian);
		if (Config.autoSteroDownmixLogging) log("Converting sound '"+filename+"'("+orignalformat.toString()+") to mono ("+monoformat.toString()+")");

		ByteBuffer bb = ByteBuffer.wrap(buff.audioData,0,buff.audioData.length);
		bb.order(bigendian ? ByteOrder.BIG_ENDIAN : ByteOrder.LITTLE_ENDIAN);
		if (bits == 8) {
			for (int i = 0; i < buff.audioData.length; i+=2) {
				bb.put(i/2,(byte)((bb.get(i)+bb.get(i+1))/2));
			}
		} else if (bits == 16) {
			for (int i = 0; i < buff.audioData.length; i+=4) {
				bb.putShort((i/2),(short)((bb.getShort(i)+bb.getShort(i+2))/2));
			}
		}
		buff.audioFormat = monoformat;
		buff.trimData(buff.audioData.length/2);
		return buff;
	}

	/**
	 * CALLED BY ASM INJECTED CODE!
	 */
	public static double calculateEntitySoundOffset(final Entity entity, final SoundEvent sound) {
		if (sound == null) return entity.getEyeHeight();
		if (stepPattern.matcher(sound.soundName.getPath()).matches()) {
			return 0;
		}

		return entity.getEyeHeight();
	}

	/**
	 * CALLED BY ASM INJECTED CODE!
	 */
	public static Vec3d computronicsOffset(Vec3d or, TileEntity te, PropertyDirection pd) {
		if (!te.hasWorld()) return or;
		EnumFacing ef = te.getWorld().getBlockState(te.getPos()).getValue(pd);
		Vec3d efv = getNormalFromFacing(ef).scale(0.51);
		return or.add(efv);
	}

	/**
	 * CALLED BY ASM INJECTED CODE!
	 */
	/*public static void onSetListener(Entity player, float partial_tick) {
		float motionX = (float)((player.posX - player.prevPosX) * 20.0d);
		float motionY = (float)((player.posY - player.prevPosY) * 20.0d);
		float motionZ = (float)((player.posZ - player.prevPosZ) * 20.0d);
		sndSystem.setListenerVelocity(motionX,motionY,motionZ);
	}*/

	/**
	 * CALLED BY ASM INJECTED CODE!
	 */
	/*public static void onIRUpdate(Vec3d velocity, String source, float pitch) {
		float motionX = (float)(velocity.x * 20.0d);
		float motionY = (float)(velocity.y * 20.0d);
		float motionZ = (float)(velocity.z * 20.0d);
		sndSystem.CommandQueue(new CommandObject(CommandObject.SET_VELOCITY, source, motionX, motionY, motionZ));
		sndSystem.CommandQueue(new CommandObject(CommandObject.SET_PITCH, source, pitch));
	}*/


	// Unused
	private static boolean isSnowingAt(BlockPos position)
	{
		return isSnowingAt(position, true);
	}

	// Copy of isRainingAt
	private static boolean isSnowingAt(BlockPos position, boolean check_rain)
	{
		if (check_rain && !mc.world.isRaining()) {
			return false;
		}
		else if (!mc.world.canSeeSky(position))
		{
			return false;
		}
		else if (mc.world.getPrecipitationHeight(position).getY() > position.getY())
		{
			return false;
		}
		else
		{
			/*boolean cansnow = mc.world.canSnowAt(position, false);
			if (mc.world.getBiome(position).getEnableSnow() && cansnow) return true;
			else if (cansnow) return true;
			else return false;*/
			return mc.world.canSnowAt(position, false) || mc.world.getBiome(position).getEnableSnow();
		}
	}

	@SuppressWarnings("deprecation")
	private static float getBlockReflectivity(final BlockPos blockPos) {
		final Block block = mc.world.getBlockState(blockPos).getBlock();
		final SoundType soundType = block.getSoundType();

		float reflectivity = 0.5f;

		if (soundType == SoundType.STONE) {
			reflectivity = Config.stoneReflectivity;
		} else if (soundType == SoundType.WOOD) {
			reflectivity = Config.woodReflectivity;
		} else if (soundType == SoundType.GROUND) {
			reflectivity = Config.groundReflectivity;
		} else if (soundType == SoundType.PLANT) {
			reflectivity = Config.plantReflectivity;
		} else if (soundType == SoundType.METAL) {
			reflectivity = Config.metalReflectivity;
		} else if (soundType == SoundType.GLASS) {
			reflectivity = Config.glassReflectivity;
		} else if (soundType == SoundType.CLOTH) {
			reflectivity = Config.clothReflectivity;
		} else if (soundType == SoundType.SAND) {
			reflectivity = Config.sandReflectivity;
		} else if (soundType == SoundType.SNOW) {
			reflectivity = Config.snowReflectivity;
		} else if (soundType == SoundType.LADDER) {
			reflectivity = Config.woodReflectivity;
		} else if (soundType == SoundType.ANVIL) {
			reflectivity = Config.metalReflectivity;
		}

		reflectivity *= Config.globalBlockReflectance;

		return reflectivity;
	}

	private static Vec3d getNormalFromFacing(final EnumFacing sideHit) {
		return new Vec3d(sideHit.getDirectionVec());
	}

	private static Vec3d reflect(final Vec3d dir, final Vec3d normal) {
		final double dot2 = dir.dotProduct(normal) * 2;

		final double x = dir.x - dot2 * normal.x;
		final double y = dir.y - dot2 * normal.y;
		final double z = dir.z - dot2 * normal.z;

		return new Vec3d(x, y, z);
	}

	private static Vec3d offsetSoundByName(final double soundX, final double soundY, final double soundZ,
			final Vec3d playerPos, final String name, final SoundCategory category) {
		double offsetX = 0.0;
		double offsetY = 0.0;
		double offsetZ = 0.0;
		double offsetTowardsPlayer = 0.0;

		double tempNormX = 0;
		double tempNormY = 0;
		double tempNormZ = 0;

		if (soundY % 1.0 < 0.001 || stepPattern.matcher(name).matches()) {
			offsetY = 0.225;
		}

		if (category == SoundCategory.BLOCKS || blockPattern.matcher(name).matches() || 
			(name == "openal" && !mc.world.isAirBlock(new BlockPos(soundX,soundY,soundZ)))) {
			// The ray will probably hit the block that it's emitting from
			// before
			// escaping. Offset the ray start position towards the player by the
			// diagonal half length of a cube

			tempNormX = playerPos.x - soundX;
			tempNormY = playerPos.y - soundY;
			tempNormZ = playerPos.z - soundZ;
			final double length = Math.sqrt(tempNormX * tempNormX + tempNormY * tempNormY + tempNormZ * tempNormZ);
			tempNormX /= length;
			tempNormY /= length;
			tempNormZ /= length;
			// 0.867 > square root of 0.5^2 * 3
			offsetTowardsPlayer = 0.867;
			offsetX += tempNormX * offsetTowardsPlayer;
			offsetY += tempNormY * offsetTowardsPlayer;
			offsetZ += tempNormZ * offsetTowardsPlayer;
		}

		return new Vec3d(soundX + offsetX, soundY + offsetY, soundZ + offsetZ);
	}

	@SuppressWarnings("deprecation")
	private static void evaluateEnvironment(final int sourceID, final float posX, final float posY, final float posZ, final SoundCategory category, final String name) {
		try {
			if (mc.player == null || mc.world == null || posY <= 0 || category == SoundCategory.RECORDS
					|| category == SoundCategory.MUSIC) {
				// posY <= 0 as a condition has to be there: Ingame
				// menu clicks do have a player and world present
				setEnvironment(sourceID, 0.0f, 0.0f, 0.0f, 0.0f, 1.0f, 1.0f, 1.0f, 1.0f, 1.0f, 1.0f, 1.0f);
				return;
			}

			final boolean isRain = rainPattern.matcher(name).matches();

			if (Config.skipRainOcclusionTracing && isRain) {
				setEnvironment(sourceID, 0.0f, 0.0f, 0.0f, 0.0f, 1.0f, 1.0f, 1.0f, 1.0f, 1.0f, 1.0f, 1.0f);
				return;
			}

			float directCutoff = 1.0f;
			final float absorptionCoeff = Config.globalBlockAbsorption * 3.0f;

			final Vec3d playerPos = new Vec3d(mc.player.posX, mc.player.posY + mc.player.getEyeHeight(), mc.player.posZ);
			final Vec3d soundPos = offsetSoundByName(posX, posY, posZ, playerPos, name, category);
			final Vec3d normalToPlayer = playerPos.subtract(soundPos).normalize();

			float airAbsorptionFactor = 1.0f;

			if (Config.snowAirAbsorptionFactor > 1.0f && mc.world.isRaining()) {
				final Vec3d middlePos = playerPos.add(soundPos).scale(0.5);
				final BlockPos playerPosBlock = new BlockPos(playerPos);
				final BlockPos soundPosBlock = new BlockPos(soundPos);
				final BlockPos middlePosBlock = new BlockPos(middlePos);
				final int snowingPlayer = isSnowingAt(playerPosBlock,false) ? 1 : 0;
				final int snowingSound = isSnowingAt(soundPosBlock,false) ? 1 : 0;
				final int snowingMiddle = isSnowingAt(middlePosBlock,false) ? 1 : 0;
				final float snowFactor = snowingPlayer * 0.25f + snowingMiddle * 0.5f + snowingSound * 0.25f;
				airAbsorptionFactor = Math.max(Config.snowAirAbsorptionFactor*mc.world.getRainStrength(1.0f)*snowFactor,airAbsorptionFactor);
			}

			/*final double distance = playerPos.distanceTo(soundPos);
			final double time = (distance/343.3)*1000;
			AL10.alSourcePause(sourceID);
			log("paused, time "+String.valueOf(time));

			new java.util.Timer().schedule(
				new java.util.TimerTask() {
					@Override
					public void run() {
						log("play, time "+String.valueOf(time));
						AL10.alSourcePlay(sourceID);
					}
				},
				(long)time
			);*/

			Vec3d rayOrigin = soundPos;

			float occlusionAccumulation = 0.0f;

			for (int i = 0; i < 10; i++) {
				final RayTraceResult rayHit = mc.world.rayTraceBlocks(rayOrigin, playerPos, true);

				if (rayHit == null) {
					break;
				}

				final Block blockHit = mc.world.getBlockState(rayHit.getBlockPos()).getBlock();

				float blockOcclusion = 1.0f;

				if (!blockHit.isOpaqueCube(blockHit.getDefaultState())) {
					// log("not a solid block!");
					blockOcclusion *= 0.15f;
				}

				occlusionAccumulation += blockOcclusion;

				rayOrigin = new Vec3d(rayHit.hitVec.x + normalToPlayer.x * 0.1, rayHit.hitVec.y + normalToPlayer.y * 0.1,
						rayHit.hitVec.z + normalToPlayer.z * 0.1);
			}

			directCutoff = (float) Math.exp(-occlusionAccumulation * absorptionCoeff);
			float directGain = (float) Math.pow(directCutoff, 0.1);

			// Calculate reverb parameters for this sound
			float sendGain0 = 0.0f;
			float sendGain1 = 0.0f;
			float sendGain2 = 0.0f;
			float sendGain3 = 0.0f;

			float sendCutoff0 = 1.0f;
			float sendCutoff1 = 1.0f;
			float sendCutoff2 = 1.0f;
			float sendCutoff3 = 1.0f;

			if (mc.player.isInsideOfMaterial(Material.WATER)) {
				directCutoff *= 1.0f - Config.underwaterFilter;
			}

			if (isRain) {
				setEnvironment(sourceID, sendGain0, sendGain1, sendGain2, sendGain3, sendCutoff0, sendCutoff1, sendCutoff2,
						sendCutoff3, directCutoff, directGain, airAbsorptionFactor);
				return;
			}

			// Shoot rays around sound
			final float phi = 1.618033988f;
			final float gAngle = phi * (float) Math.PI * 2.0f;
			final float maxDistance = Config.maxDistance;

			final int numRays = Config.environmentEvaluationRays;
			final int rayBounces = 4;

			final float[] bounceReflectivityRatio = new float[rayBounces];

			float sharedAirspace = 0.0f;

			final float rcpTotalRays = 1.0f / (numRays * rayBounces);
			final float rcpPrimaryRays = 1.0f / numRays;

			for (int i = 0; i < numRays; i++) {
				final float fi = i;
				final float fiN = fi / numRays;
				final float longitude = gAngle * fi;
				final float latitude = (float) Math.asin(fiN * 2.0f - 1.0f);

				final Vec3d rayDir = new Vec3d(Math.cos(latitude) * Math.cos(longitude),
						Math.cos(latitude) * Math.sin(longitude), Math.sin(latitude));

				final Vec3d rayStart = new Vec3d(soundPos.x, soundPos.y, soundPos.z);

				final Vec3d rayEnd = new Vec3d(rayStart.x + rayDir.x * maxDistance, rayStart.y + rayDir.y * maxDistance,
						rayStart.z + rayDir.z * maxDistance);

				final RayTraceResult rayHit = mc.world.rayTraceBlocks(rayStart, rayEnd, true);

				if (rayHit != null) {
					final double rayLength = soundPos.distanceTo(rayHit.hitVec);

					// Additional bounces
					BlockPos lastHitBlock = rayHit.getBlockPos();
					Vec3d lastHitPos = rayHit.hitVec;
					Vec3d lastHitNormal = getNormalFromFacing(rayHit.sideHit);
					Vec3d lastRayDir = rayDir;

					float totalRayDistance = (float) rayLength;

					// Secondary ray bounces
					for (int j = 0; j < rayBounces; j++) {
						final Vec3d newRayDir = reflect(lastRayDir, lastHitNormal);
						// Vec3d newRayDir = lastHitNormal;
						final Vec3d newRayStart = new Vec3d(lastHitPos.x + lastHitNormal.x * 0.01,
								lastHitPos.y + lastHitNormal.y * 0.01, lastHitPos.z + lastHitNormal.z * 0.01);
						final Vec3d newRayEnd = new Vec3d(newRayStart.x + newRayDir.x * maxDistance,
								newRayStart.y + newRayDir.y * maxDistance, newRayStart.z + newRayDir.z * maxDistance);

						final RayTraceResult newRayHit = mc.world.rayTraceBlocks(newRayStart, newRayEnd, true);

						float energyTowardsPlayer = 0.25f;
						final float blockReflectivity = getBlockReflectivity(lastHitBlock);
						energyTowardsPlayer *= blockReflectivity * 0.75f + 0.25f;

						if (newRayHit == null) {
							totalRayDistance += lastHitPos.distanceTo(playerPos);
						} else {
							final double newRayLength = lastHitPos.distanceTo(newRayHit.hitVec);

							bounceReflectivityRatio[j] += blockReflectivity;

							totalRayDistance += newRayLength;

							lastHitPos = newRayHit.hitVec;
							lastHitNormal = getNormalFromFacing(newRayHit.sideHit);
							lastRayDir = newRayDir;
							lastHitBlock = newRayHit.getBlockPos();

							// Cast one final ray towards the player. If it's
							// unobstructed, then the sound source and the player
							// share airspace.
							if (Config.simplerSharedAirspaceSimulation && j == rayBounces - 1
									|| !Config.simplerSharedAirspaceSimulation) {
								final Vec3d finalRayStart = new Vec3d(lastHitPos.x + lastHitNormal.x * 0.01,
										lastHitPos.y + lastHitNormal.y * 0.01, lastHitPos.z + lastHitNormal.z * 0.01);

								final RayTraceResult finalRayHit = mc.world.rayTraceBlocks(finalRayStart, playerPos, true);

								if (finalRayHit == null) {
									// log("Secondary ray hit the player!");
									sharedAirspace += 1.0f;
								}
							}
						}

						final float reflectionDelay = (float) Math.max(totalRayDistance, 0.0) * 0.12f * blockReflectivity;

						final float cross0 = 1.0f - MathHelper.clamp(Math.abs(reflectionDelay - 0.0f), 0.0f, 1.0f);
						final float cross1 = 1.0f - MathHelper.clamp(Math.abs(reflectionDelay - 1.0f), 0.0f, 1.0f);
						final float cross2 = 1.0f - MathHelper.clamp(Math.abs(reflectionDelay - 2.0f), 0.0f, 1.0f);
						final float cross3 = MathHelper.clamp(reflectionDelay - 2.0f, 0.0f, 1.0f);

						sendGain0 += cross0 * energyTowardsPlayer * 6.4f * rcpTotalRays;
						sendGain1 += cross1 * energyTowardsPlayer * 12.8f * rcpTotalRays;
						sendGain2 += cross2 * energyTowardsPlayer * 12.8f * rcpTotalRays;
						sendGain3 += cross3 * energyTowardsPlayer * 12.8f * rcpTotalRays;

						// Nowhere to bounce off of, stop bouncing!
						if (newRayHit == null) {
							break;
						}
					}
				}

			}

			// log("total reflectivity ratio: " + totalReflectivityRatio);

			bounceReflectivityRatio[0] = bounceReflectivityRatio[0] / numRays;
			bounceReflectivityRatio[1] = bounceReflectivityRatio[1] / numRays;
			bounceReflectivityRatio[2] = bounceReflectivityRatio[2] / numRays;
			bounceReflectivityRatio[3] = bounceReflectivityRatio[3] / numRays;

			sharedAirspace *= 64.0f;

			if (Config.simplerSharedAirspaceSimulation) {
				sharedAirspace *= rcpPrimaryRays;
			} else {
				sharedAirspace *= rcpTotalRays;
			}

			final float sharedAirspaceWeight0 = MathHelper.clamp(sharedAirspace / 20.0f, 0.0f, 1.0f);
			final float sharedAirspaceWeight1 = MathHelper.clamp(sharedAirspace / 15.0f, 0.0f, 1.0f);
			final float sharedAirspaceWeight2 = MathHelper.clamp(sharedAirspace / 10.0f, 0.0f, 1.0f);
			final float sharedAirspaceWeight3 = MathHelper.clamp(sharedAirspace / 10.0f, 0.0f, 1.0f);

			sendCutoff0 = (float) Math.exp(-occlusionAccumulation * absorptionCoeff * 1.0f) * (1.0f - sharedAirspaceWeight0)
					+ sharedAirspaceWeight0;
			sendCutoff1 = (float) Math.exp(-occlusionAccumulation * absorptionCoeff * 1.0f) * (1.0f - sharedAirspaceWeight1)
					+ sharedAirspaceWeight1;
			sendCutoff2 = (float) Math.exp(-occlusionAccumulation * absorptionCoeff * 1.5f) * (1.0f - sharedAirspaceWeight2)
					+ sharedAirspaceWeight2;
			sendCutoff3 = (float) Math.exp(-occlusionAccumulation * absorptionCoeff * 1.5f) * (1.0f - sharedAirspaceWeight3)
					+ sharedAirspaceWeight3;

			// attempt to preserve directionality when airspace is shared by
			// allowing some of the dry signal through but filtered
			final float averageSharedAirspace = (sharedAirspaceWeight0 + sharedAirspaceWeight1 + sharedAirspaceWeight2
					+ sharedAirspaceWeight3) * 0.25f;
			directCutoff = Math.max((float) Math.pow(averageSharedAirspace, 0.5) * 0.2f, directCutoff);

			directGain = (float) Math.pow(directCutoff, 0.1);

			sendGain1 *= bounceReflectivityRatio[1];
			sendGain2 *= (float) Math.pow(bounceReflectivityRatio[2], 3.0);
			sendGain3 *= (float) Math.pow(bounceReflectivityRatio[3], 4.0);

			sendGain0 = MathHelper.clamp(sendGain0, 0.0f, 1.0f);
			sendGain1 = MathHelper.clamp(sendGain1, 0.0f, 1.0f);
			sendGain2 = MathHelper.clamp(sendGain2 * 1.05f - 0.05f, 0.0f, 1.0f);
			sendGain3 = MathHelper.clamp(sendGain3 * 1.05f - 0.05f, 0.0f, 1.0f);

			sendGain0 *= (float) Math.pow(sendCutoff0, 0.1);
			sendGain1 *= (float) Math.pow(sendCutoff1, 0.1);
			sendGain2 *= (float) Math.pow(sendCutoff2, 0.1);
			sendGain3 *= (float) Math.pow(sendCutoff3, 0.1);

			if (mc.player.isInWater()) {
				sendCutoff0 *= 0.4f;
				sendCutoff1 *= 0.4f;
				sendCutoff2 *= 0.4f;
				sendCutoff3 *= 0.4f;
			}

			setEnvironment(sourceID, sendGain0, sendGain1, sendGain2, sendGain3, sendCutoff0, sendCutoff1, sendCutoff2,
					sendCutoff3, directCutoff, directGain, airAbsorptionFactor);
		} catch(Exception e) {
			logError("Error while evaluation environment:");
			e.printStackTrace();
			setEnvironment(sourceID, 0.0f, 0.0f, 0.0f, 0.0f, 1.0f, 1.0f, 1.0f, 1.0f, 1.0f, 1.0f, 1.0f);
		}
	}

	private static void setEnvironment(final int sourceID, final float sendGain0, final float sendGain1,
			final float sendGain2, final float sendGain3, final float sendCutoff0, final float sendCutoff1,
			final float sendCutoff2, final float sendCutoff3, final float directCutoff, final float directGain,
			final float airAbsorptionFactor) {
		// Set reverb send filter values and set source to send to all reverb fx
		// slots
		EFX10.alFilterf(sendFilter0, EFX10.AL_LOWPASS_GAIN, sendGain0);
		EFX10.alFilterf(sendFilter0, EFX10.AL_LOWPASS_GAINHF, sendCutoff0);
		AL11.alSource3i(sourceID, EFX10.AL_AUXILIARY_SEND_FILTER, auxFXSlot0, 0, sendFilter0);

		EFX10.alFilterf(sendFilter1, EFX10.AL_LOWPASS_GAIN, sendGain1);
		EFX10.alFilterf(sendFilter1, EFX10.AL_LOWPASS_GAINHF, sendCutoff1);
		AL11.alSource3i(sourceID, EFX10.AL_AUXILIARY_SEND_FILTER, auxFXSlot1, 1, sendFilter1);

		EFX10.alFilterf(sendFilter2, EFX10.AL_LOWPASS_GAIN, sendGain2);
		EFX10.alFilterf(sendFilter2, EFX10.AL_LOWPASS_GAINHF, sendCutoff2);
		AL11.alSource3i(sourceID, EFX10.AL_AUXILIARY_SEND_FILTER, auxFXSlot2, 2, sendFilter2);

		EFX10.alFilterf(sendFilter3, EFX10.AL_LOWPASS_GAIN, sendGain3);
		EFX10.alFilterf(sendFilter3, EFX10.AL_LOWPASS_GAINHF, sendCutoff3);
		AL11.alSource3i(sourceID, EFX10.AL_AUXILIARY_SEND_FILTER, auxFXSlot3, 3, sendFilter3);

		EFX10.alFilterf(directFilter0, EFX10.AL_LOWPASS_GAIN, directGain);
		EFX10.alFilterf(directFilter0, EFX10.AL_LOWPASS_GAINHF, directCutoff);
		AL10.alSourcei(sourceID, EFX10.AL_DIRECT_FILTER, directFilter0);

		AL10.alSourcef(sourceID, EFX10.AL_AIR_ABSORPTION_FACTOR, MathHelper.clamp(Config.airAbsorption * airAbsorptionFactor,0.0f,10.0f));
	}

	/**
	 * Applies the parameters in the enum ReverbParams to the main reverb
	 * effect.
	 */
	protected static void setReverbParams(final ReverbParams r, final int auxFXSlot, final int reverbSlot) {
		EFX10.alEffectf(reverbSlot, EFX10.AL_EAXREVERB_DENSITY, r.density);
		checkErrorLog("Error while assigning reverb density: " + r.density);

		EFX10.alEffectf(reverbSlot, EFX10.AL_EAXREVERB_DIFFUSION, r.diffusion);
		checkErrorLog("Error while assigning reverb diffusion: " + r.diffusion);

		EFX10.alEffectf(reverbSlot, EFX10.AL_EAXREVERB_GAIN, r.gain);
		checkErrorLog("Error while assigning reverb gain: " + r.gain);

		EFX10.alEffectf(reverbSlot, EFX10.AL_EAXREVERB_GAINHF, r.gainHF);
		checkErrorLog("Error while assigning reverb gainHF: " + r.gainHF);

		EFX10.alEffectf(reverbSlot, EFX10.AL_EAXREVERB_DECAY_TIME, r.decayTime);
		checkErrorLog("Error while assigning reverb decayTime: " + r.decayTime);

		EFX10.alEffectf(reverbSlot, EFX10.AL_EAXREVERB_DECAY_HFRATIO, r.decayHFRatio);
		checkErrorLog("Error while assigning reverb decayHFRatio: " + r.decayHFRatio);

		EFX10.alEffectf(reverbSlot, EFX10.AL_EAXREVERB_REFLECTIONS_GAIN, r.reflectionsGain);
		checkErrorLog("Error while assigning reverb reflectionsGain: " + r.reflectionsGain);

		EFX10.alEffectf(reverbSlot, EFX10.AL_EAXREVERB_LATE_REVERB_GAIN, r.lateReverbGain);
		checkErrorLog("Error while assigning reverb lateReverbGain: " + r.lateReverbGain);

		EFX10.alEffectf(reverbSlot, EFX10.AL_EAXREVERB_LATE_REVERB_DELAY, r.lateReverbDelay);
		checkErrorLog("Error while assigning reverb lateReverbDelay: " + r.lateReverbDelay);

		EFX10.alEffectf(reverbSlot, EFX10.AL_EAXREVERB_AIR_ABSORPTION_GAINHF, r.airAbsorptionGainHF);
		checkErrorLog("Error while assigning reverb airAbsorptionGainHF: " + r.airAbsorptionGainHF);

		EFX10.alEffectf(reverbSlot, EFX10.AL_EAXREVERB_ROOM_ROLLOFF_FACTOR, r.roomRolloffFactor);
		checkErrorLog("Error while assigning reverb roomRolloffFactor: " + r.roomRolloffFactor);

		// Attach updated effect object
		EFX10.alAuxiliaryEffectSloti(auxFXSlot, EFX10.AL_EFFECTSLOT_EFFECT, reverbSlot);
	}

	public static void log(final String message) {
		logger.info(message);
	}

	public static void logError(final String errorMessage) {
		logger.error(errorMessage);
	}

	protected static boolean checkErrorLog(final String errorMessage) {
		final int error = AL10.alGetError();
		if (error == AL10.AL_NO_ERROR) {
			return false;
		}

		String errorName;

		switch (error) {
		case AL10.AL_INVALID_NAME:
			errorName = "AL_INVALID_NAME";
			break;
		case AL10.AL_INVALID_ENUM:
			errorName = "AL_INVALID_ENUM";
			break;
		case AL10.AL_INVALID_VALUE:
			errorName = "AL_INVALID_VALUE";
			break;
		case AL10.AL_INVALID_OPERATION:
			errorName = "AL_INVALID_OPERATION";
			break;
		case AL10.AL_OUT_OF_MEMORY:
			errorName = "AL_OUT_OF_MEMORY";
			break;
		default:
			errorName = Integer.toString(error);
			break;
		}

		logError(errorMessage + " OpenAL error " + errorName);
		return true;
	}

}
