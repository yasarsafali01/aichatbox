package com.yasarsafali.rag_backend.service.locate;

import java.io.File;
import java.util.List;

public interface LocatableExtractor {

    boolean supports(File file);

    List<LocatableUnit> extract(File file);
}
