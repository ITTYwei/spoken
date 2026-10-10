package cn.adinall.ai.domain.business.conversation.model.valobj;

/** 一段识别结果。finalChunk=false 表示还会被后面的增量修正，界面可以先用着但别落库。 */
public record TranscriptChunkVO(String text, boolean finalChunk, long startMs, long endMs) {
}