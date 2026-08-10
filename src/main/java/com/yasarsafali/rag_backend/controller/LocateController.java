package com.yasarsafali.rag_backend.controller;

import java.util.List;

import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.yasarsafali.rag_backend.dto.locate.LocateRequest;
import com.yasarsafali.rag_backend.dto.locate.LocateResult;
import com.yasarsafali.rag_backend.service.ProfanityFilterService;
import com.yasarsafali.rag_backend.service.locate.LocateService;

@RestController
@RequestMapping("/locate")
public class LocateController {

    private final LocateService locateService;
    private final ProfanityFilterService profanityFilterService;

    public LocateController(LocateService locateService, ProfanityFilterService profanityFilterService) {
        this.locateService = locateService;
        this.profanityFilterService = profanityFilterService;
    }

    @PostMapping
    public List<LocateResult> locate(@RequestBody LocateRequest request) {
        profanityFilterService.assertClean(request.question());
        return locateService.locate(request.question());
    }
}
