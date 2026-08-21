package com.gitissuesolver.backend.agent.dto;

import java.util.List;

public record AnalyzeResponse(
        String plan,
        List<String> relevantFiles
) {}
