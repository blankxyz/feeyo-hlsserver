package com.feeyo.hls;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

import org.apache.commons.lang.ArrayUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.feeyo.hls.ads.AdsMagr;
import com.feeyo.hls.ts.TsSegment;
import com.feeyo.hls.ts.segmenter.AacH264MixedTsSegmenter;
import com.feeyo.hls.ts.segmenter.AacTranscodingTsSegmenter;
import com.feeyo.hls.ts.segmenter.AacTsSegmenter;
import com.feeyo.hls.ts.segmenter.AbstractTsSegmenter;
import com.feeyo.hls.ts.segmenter.H264TranscodingTsSegmenter;
import com.feeyo.hls.ts.segmenter.H264TsSegmenter;
import com.google.common.primitives.Longs;


/**
 * HLS 在线流
 * 
 * cache the media for N ts segment, waiting for the client to access
 * 
 * @author tribf wangyamin@variflight.com
 * @author xuwenfeng
 * @author zhuam
 */
public class HlsLiveStream {
	
	private static Logger LOGGER = LoggerFactory.getLogger( HlsLiveStream.class );
    
    // id -> client session
    private Map<String, HlsClientSession> clientSessions = new ConcurrentHashMap<String, HlsClientSession>();
    
    private long mtime;
    
    private long streamId;
    private int streamType;
    
    // audio
    private float sampleRate;
    private int sampleSizeInBits;
    private int channels;
    
    // video
    private int fps;
    
    // asias
    private List<String> aliasNames;

    //
    private AbstractTsSegmenter tsSegmenter = null;
    
    //
    private Map<Long, TsSegment> tsSegments = new ConcurrentHashMap<Long, TsSegment>(); 		
    private AtomicLong tsIndexGen = new AtomicLong(4);										//  ads 1,2,3   normal 4...  

    public HlsLiveStream(Long streamId, Integer streamType, List<String> aliasNames, 
    		Float sampleRate, Integer sampleSizeInBits, Integer channels, Integer fps) {
        
    	this.mtime = System.currentTimeMillis();
    	
    	this.streamId = streamId;
    	this.streamType = streamType;
    	
    	this.aliasNames = aliasNames;
    	
        this.sampleRate = sampleRate == null ? 8000F : sampleRate;
        this.sampleSizeInBits = sampleSizeInBits == null ? 16: sampleSizeInBits;
        this.channels = channels == null ? 1 : channels;
        this.fps = fps == null ? 25: fps;
        
        
        switch( streamType ) {
    	case HlsLiveStreamType.PCM:
    		tsSegmenter = new AacTranscodingTsSegmenter();
    		break;
    	case HlsLiveStreamType.AAC:
    		tsSegmenter = new AacTsSegmenter();
    		break;
    	case HlsLiveStreamType.YUV:
    		tsSegmenter = new H264TranscodingTsSegmenter();
    		break;
    	case HlsLiveStreamType.H264:
    		tsSegmenter = new H264TsSegmenter();
    		break;
    	case HlsLiveStreamType.AAC_H264_MIXED:
    		tsSegmenter = new AacH264MixedTsSegmenter();
    		break;
    	}
        
        tsSegmenter.initialize(sampleRate, sampleSizeInBits, channels, fps);
    }
    
    
    //
    public void removeExpireSessionAndTsSegments(long now, int timeout) {
    	
    	long minTsIndex = -1;
 
		for (HlsClientSession session : clientSessions.values()) {
			
			// get min TS Index
			long[] tsIndexs = session.getOldTsIndexs();
			if ( tsIndexs != null ) {
				long tmpTsIndex = Longs.min(tsIndexs);
				if ( minTsIndex == -1 || minTsIndex > tmpTsIndex )  {
					minTsIndex = tmpTsIndex;
				} 
			}
			
			// remove expire session
			if (now - session.getMtime() > timeout) {
				clientSessions.remove( session.getId() );
				LOGGER.info("remove hls client: " + session.toString() + " left: " + clientSessions.size());
			}
		}
    	
		
		// remove expire TS 
		if ( tsSegments.size() > 24 ) {
			LOGGER.info(" liveStream id={}, TS SEGMENT is too mach, size={}", streamId,  tsSegments.size() );
		}
		
		for(Map.Entry<Long, TsSegment> entry:  tsSegments.entrySet() ) {
			long tsIndex =  entry.getKey();
			TsSegment tsSegment = entry.getValue();
			
			if ( System.currentTimeMillis() - tsSegment.getLasttime() > timeout  || (minTsIndex > tsIndex) ) {
				tsSegments.remove( tsIndex );
				LOGGER.info("remove ts= {}, minTsIndex= {} ", tsSegment, minTsIndex);
			} 
		}
		
		
    }
    
    // length= 3 ~ 5
    public long[] fetchTsIndexs() {
    	// 
    	Set<Long> indexSET = tsSegments.keySet();
    	if ( indexSET.size() < 3 ) {
    		return null;
    	}	
    	
    	//
    	Long[] indexArr = indexSET.toArray(new Long[indexSET.size()]);
    	Arrays.sort( indexArr );
    	
    	if ( indexArr.length > 5 ) {
    		Long[] tmpArr = new Long[5];
    		System.arraycopy(indexArr, indexArr.length - 5, tmpArr, 0, 5);
    		
    		return ArrayUtils.toPrimitive( tmpArr );
    		
    	} else {
    		return ArrayUtils.toPrimitive( indexArr );
    	}
    }
    
    public TsSegment fetchTsSegmentByIndex(long index) {
    	if ( index < 0 )
    		return null;
    	
    	TsSegment tsSegment = null;
    	if ( index < 4 ) {
    		
    		String type = "audio";
    		switch( streamType ) {
        	case HlsLiveStreamType.YUV:
        	case HlsLiveStreamType.H264:
        		type = "video";
        		break;
        	case HlsLiveStreamType.AAC_H264_MIXED:
        		type = "mixed";
        		break;
        	}
    		
    		List<TsSegment> adTsSegments = AdsMagr.getAdsTsSegments(type, sampleRate, sampleSizeInBits, channels, fps);
    		tsSegment = adTsSegments.get((int)index - 1);
    	} else {
    		tsSegment = tsSegments.get( index );
    	}
		
    	if ( tsSegment != null ) {
    		tsSegment.setLasttime(  System.currentTimeMillis() );
    	}
    	
    	return tsSegment;
	}
    
    //
    public HlsClientSession newClientSession() {
        
        HlsClientSession clientSession = new HlsClientSession(this);
        clientSessions.put(clientSession.getId(), clientSession);
        
        LOGGER.info("add client: " + clientSession.toString());
        return clientSession;
    }
    
    public HlsClientSession getClientSessionsById(String id) {
    	HlsClientSession clientSession = clientSessions.get(id);
    	return clientSession;
    }
    
    public Map<String, HlsClientSession> getAllClientSession() {
        return clientSessions;
    }
    
    public void close() {
    	if ( clientSessions != null)
    		clientSessions.clear();
        
        if ( tsSegmenter != null)
        	tsSegmenter.close();
    }

    public long getMtime() {
        return mtime;
    }

    public long getStreamId() {
        return streamId;
    }
    
    public int getStreamType() {
		return streamType;
	}


	// 采样率
    public float getSampleRate() {
		return sampleRate;
	}

	public int getSampleSizeInBits() {
		return sampleSizeInBits;
	}

	public int getChannels() {
		return channels;
	}

	public int getFps() {
		return fps;
	}

	public List<String> getAliasNames() {
		return aliasNames;
	}

	public void setAliasNames(List<String> aliasNames) {
		this.aliasNames = aliasNames;
	}


	// +++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++
	public synchronized void addAvStream(byte rawType, byte[] rawReserved, byte[] rawData, byte[] reserved) {
    	
    	this.mtime = System.currentTimeMillis();

    	if( tsSegmenter != null) {
	        byte[] tsData = tsSegmenter.getTsBuf( rawType, rawData, reserved );
	        if ( tsData != null) {
	        	
	        	long tsIndex = tsIndexGen.getAndIncrement();
	            TsSegment tsSegment = new TsSegment(  tsIndex +".ts", tsData, tsSegmenter.getTsSegTime(), false);
	            tsSegments.put(tsIndex, tsSegment);
	            
	            LOGGER.info("add ts {} ", tsSegment);
	        }
    	}
    }

}